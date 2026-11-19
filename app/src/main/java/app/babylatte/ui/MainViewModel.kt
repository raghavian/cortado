package app.babylatte.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.babylatte.BabylatteApp
import app.babylatte.data.BoundingBox
import app.babylatte.data.Category
import app.babylatte.data.Filters
import app.babylatte.data.GeoResult
import app.babylatte.data.NewPlace
import app.babylatte.data.OfflineStatus
import app.babylatte.data.Pending
import app.babylatte.data.Place
import app.babylatte.data.Report
import app.babylatte.data.SubmittedReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Instructions for the map view, e.g. after a city search. */
sealed interface MapCommand {
    data class MoveTo(val lat: Double, val lon: Double, val zoom: Double) : MapCommand
    data class Fit(val bbox: BoundingBox) : MapCommand
    /** Zoom in on a place and keep it in the top part of the screen, above the details sheet. */
    data class Focus(val lat: Double, val lon: Double) : MapCommand
    data object LocateMe : MapCommand
}

sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState
    data class Sent(val noteUrl: String) : ReportState
    data class Failed(val message: String) : ReportState
}

data class UiState(
    val places: List<Place> = emptyList(),
    val visible: List<Place> = emptyList(),
    val loading: Boolean = false,
    /** True when a load has been running long enough that we should explain the wait. */
    val slow: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val filters: Set<String> = emptySet(),
    val category: Category = Category.CAFES,
    val favorites: Map<String, Place> = emptyMap(),
    val selected: Place? = null,
    val areaChanged: Boolean = false,
    val userLat: Double? = null,
    val userLon: Double? = null,
    val searchResults: List<GeoResult> = emptyList(),
    /** True when [searchResults] came from the offline data, so the dialog offers a worldwide search. */
    val searchResultsOffline: Boolean = false,
    /** Cafés whose name matches the search, shown above the area results. */
    val placeResults: List<Place> = emptyList(),
    val lastQuery: String = "",
    val offline: OfflineStatus = OfflineStatus(),
    val pending: List<Place> = emptyList(),
    val reports: List<SubmittedReport> = emptyList(),
    /** Add-a-café flow: placing the pin, then possibly confirming it isn't a duplicate, then the form. */
    val addStep: AddStep = AddStep.None,
)

sealed interface AddStep {
    data object None : AddStep
    data object Placing : AddStep
    data class CheckDuplicates(val lat: Double, val lon: Double, val nearby: List<Place>) : AddStep
    data class Form(val lat: Double, val lon: Double) : AddStep
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val deps = app as BabylatteApp
    private val store = deps.store

    private val core = MutableStateFlow(UiState())
    private var lastBox: BoundingBox? = null
    private var currentBox: BoundingBox? = null
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var loadJob: Job? = null
    private var autoJob: Job? = null

    private val _commands = Channel<MapCommand>(Channel.BUFFERED)
    val commands = _commands.receiveAsFlow()

    val reportState = MutableStateFlow<ReportState>(ReportState.Idle)

    val state: StateFlow<UiState> = combine(core, store.favorites, deps.offline.status, store.pending, store.reports) { s, favs, offline, pending, reports ->
        val resolved = Pending.resolved(pending, s.places)
        val shownPending = pending.filter { it.id !in resolved }
        val all = s.places + shownPending
        val visible = Filters.sort(all.filter { Filters.matches(it, s.filters) }, s.userLat, s.userLon)
        s.copy(favorites = favs, visible = visible, offline = offline, pending = shownPending, reports = reports)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    /** Saved camera position, or Copenhagen on first launch. */
    suspend fun initialCamera(): Triple<Double, Double, Double> =
        store.camera.first() ?: Triple(55.6794, 12.5800, 15.0)

    init {
        viewModelScope.launch {
            val filters = store.filters.first()
            val category = store.category.first()
            core.update { it.copy(filters = filters, category = category) }
        }
        // Once a user-added place shows up in the real data, forget the local pending copy.
        viewModelScope.launch {
            combine(core, store.pending) { s, pending -> Pending.resolved(pending, s.places) }
                .collect { resolved ->
                    store.markOnMap(resolved)
                    store.removePending(resolved)
                }
        }
        refreshReports()
        // First launch downloads offline data; afterwards refresh if it's more than a day old.
        // (SyncWorker also refreshes daily in the background.)
        val age = deps.offline.ageMillis()
        if (age == null || age > DAY_MS) {
            val hadData = deps.offline.hasData
            viewModelScope.launch {
                if (deps.offline.sync() && !hadData) {
                    lastBox = null
                    loadArea()
                }
            }
        }
    }

    /** Called by the map whenever the visible area settles. */
    fun onViewportChanged(box: BoundingBox, centerLat: Double, centerLon: Double, zoom: Double) {
        currentBox = box
        this.centerLat = centerLat
        this.centerLon = centerLon
        viewModelScope.launch { store.setCamera(centerLat, centerLon, zoom) }
        val last = lastBox
        when {
            last == null -> loadArea()
            // Offline data is instant, so just follow the map (after a search, pan or zoom).
            deps.offline.hasData -> autoLoad(box)
            else -> core.update { it.copy(areaChanged = !last.containsMostOf(box)) }
        }
    }

    /**
     * Reloads pins from offline data whenever the map moves. Outside the offline regions it only
     * shows the "Search this area" button, so we never hit the slow live servers on every pan.
     */
    private fun autoLoad(box: BoundingBox) {
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            val places = deps.offline.query(box, core.value.category)
            if (places == null) {
                val last = lastBox
                core.update { it.copy(areaChanged = last == null || !last.containsMostOf(box)) }
                return@launch
            }
            loadJob?.cancel() // a live search for the previous area is no longer needed
            lastBox = box
            core.update { it.copy(places = places, areaChanged = false, loading = false, slow = false) }
        }
    }

    fun loadArea() {
        val box = currentBox ?: return
        loadJob?.cancel()
        val category = core.value.category
        loadJob = viewModelScope.launch {
            core.update { it.copy(error = null, info = null, areaChanged = false, slow = false) }

            // Offline data on the phone: instant, no network needed.
            if (!deps.offline.hasData && deps.offline.status.value.syncing) {
                core.update { it.copy(loading = true) }
                deps.offline.status.first { !it.syncing } // first-launch download in progress
                core.update { it.copy(loading = false) }
            }
            deps.offline.query(box, category)?.let { places ->
                lastBox = box
                core.update {
                    it.copy(places = places, info = if (places.isEmpty()) "No cafés found in this area." else null)
                }
                return@launch
            }

            // Live search below: keep the area small so Overpass queries stay fast and polite.
            if (box.areaDeg2 > MAX_AREA_DEG2) {
                core.update { it.copy(info = "Zoom in a little to search for cafés here.", areaChanged = true) }
                return@launch
            }

            // Show saved results straight away; skip the network if they are recent enough.
            val cached = deps.cache.find(box, category)
            if (cached != null) {
                lastBox = cached.bbox
                core.update { it.copy(places = cached.places) }
                if (System.currentTimeMillis() - cached.fetchedAt < CACHE_FRESH_MS) return@launch
            }

            core.update { it.copy(loading = true) }
            val slowTimer = launch {
                delay(8_000)
                core.update { it.copy(slow = true) }
            }
            try {
                // Outside the offline regions: live Overpass.
                val places = deps.overpass.fetch(box, category)
                lastBox = box
                deps.cache.put(box, category, places)
                core.update {
                    it.copy(places = places, info = if (places.isEmpty()) "No cafés found in this area." else null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Something went wrong."
                core.update {
                    if (cached != null) it.copy(info = "Showing saved results. $msg")
                    else it.copy(error = msg, areaChanged = true)
                }
            } finally {
                slowTimer.cancel()
                core.update { it.copy(loading = false, slow = false) }
            }
        }
    }

    fun toggleFilter(key: String) {
        val next = core.value.filters.let { if (key in it) it - key else it + key }
        core.update { it.copy(filters = next) }
        viewModelScope.launch { store.setFilters(next) }
    }

    fun clearFilters() {
        core.update { it.copy(filters = emptySet()) }
        viewModelScope.launch { store.setFilters(emptySet()) }
    }

    fun setCategory(c: Category) {
        if (c == core.value.category) return
        core.update { it.copy(category = c) }
        viewModelScope.launch { store.setCategory(c) }
        loadArea()
    }

    fun select(place: Place?) = core.update { it.copy(selected = place) }

    fun toggleFavorite(place: Place) = viewModelScope.launch { store.toggleFavorite(place) }

    fun onUserLocation(lat: Double, lon: Double) = core.update { it.copy(userLat = lat, userLon = lon) }

    fun locateMe() = _commands.trySend(MapCommand.LocateMe)

    fun showOnMap(place: Place) {
        _commands.trySend(MapCommand.Focus(place.lat, place.lon))
        select(place)
    }

    fun dismissMessage() = core.update { it.copy(error = null, info = null) }

    /**
     * Search box: café names first (offline data, loaded areas and your pending places), then
     * cities / neighbourhoods / addresses via Nominatim.
     */
    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            val q = Pending.normalize(query)
            val refLat = state.value.userLat ?: centerLat
            val refLon = state.value.userLon ?: centerLon
            val matches = (deps.offline.searchByName(query) + state.value.places + state.value.pending)
                .distinctBy { it.id }
                .filter { Pending.normalize(it.name).contains(q) }
                .sortedWith(
                    compareByDescending<Place> { Pending.normalize(it.name) == q }
                        .thenBy { Filters.distanceMeters(refLat, refLon, it.lat, it.lon) },
                )
            when {
                matches.size == 1 -> { showOnMap(matches.first()); return@launch }
                matches.isNotEmpty() -> {
                    core.update { it.copy(placeResults = matches.take(10), lastQuery = query.trim()) }
                    return@launch
                }
            }
            searchLocation(query)
        }
    }

    /**
     * Search towns, neighbourhoods and streets (also offered from the café results dialog).
     * Uses the offline data first, so most searches need no internet and put no load on
     * OpenStreetMap's servers; Nominatim is only asked when nothing offline matches.
     */
    fun searchLocation(query: String) {
        core.update { it.copy(placeResults = emptyList(), lastQuery = query.trim()) }
        viewModelScope.launch {
            val offline = deps.offline.searchAreas(query, state.value.userLat ?: centerLat, state.value.userLon ?: centerLon)
            when {
                offline.size == 1 && !query.any(Char::isDigit) -> goTo(offline.first())
                offline.isNotEmpty() -> core.update { it.copy(searchResults = offline, searchResultsOffline = true) }
                else -> searchOnline(query)
            }
        }
    }

    /** Worldwide search via Nominatim, for places outside the offline data and exact addresses. */
    fun searchOnline(query: String) {
        core.update { it.copy(searchResults = emptyList(), placeResults = emptyList()) }
        viewModelScope.launch {
            try {
                val results = deps.geocoding.search(query.trim())
                when {
                    results.isEmpty() -> core.update { it.copy(info = "No place found for \"$query\".") }
                    results.size == 1 -> goTo(results.first())
                    else -> core.update { it.copy(searchResults = results, searchResultsOffline = false) }
                }
            } catch (e: Exception) {
                core.update { it.copy(error = "Search failed: ${e.message}") }
            }
        }
    }

    fun goTo(result: GeoResult) {
        core.update { it.copy(searchResults = emptyList()) }
        val bb = result.bbox
        // Fit small areas (a neighbourhood); for whole cities, zoom to the centre instead.
        // Point-like results (some neighbourhoods are a single node) also zoom to the centre.
        if (bb != null && bb.areaDeg2 in MIN_FIT_AREA_DEG2..MAX_AREA_DEG2) _commands.trySend(MapCommand.Fit(bb))
        else _commands.trySend(MapCommand.MoveTo(result.lat, result.lon, result.zoom))
        lastBox = null // force a fresh load once the map settles
    }

    fun clearSearchResults() = core.update { it.copy(searchResults = emptyList(), placeResults = emptyList()) }

    fun pickSearchResult(place: Place) {
        core.update { it.copy(placeResults = emptyList()) }
        showOnMap(place)
    }

    fun sendReport(place: Place, report: Report) {
        reportState.value = ReportState.Sending
        viewModelScope.launch {
            reportState.value = try {
                val note = deps.notes.createNote(place, report)
                store.addReport(
                    SubmittedReport(
                        noteId = note.id, noteUrl = note.url, placeId = place.id, placeName = place.name,
                        isNewPlace = false, createdAt = System.currentTimeMillis(), reportedTags = report.facilityTags(),
                    ),
                )
                ReportState.Sent(note.url)
            } catch (e: Exception) {
                ReportState.Failed(e.message ?: "Could not send the report.")
            }
        }
    }

    // --- Adding a missing café -------------------------------------------------------------

    fun startAdd() {
        select(null)
        core.update { it.copy(addStep = AddStep.Placing) }
    }

    fun cancelAdd() = core.update { it.copy(addStep = AddStep.None) }

    /** The pin is where the user wants it (map centre). Check for duplicates first. */
    fun confirmLocation() {
        val lat = centerLat
        val lon = centerLon
        val nearby = Pending.nearby(state.value.places + state.value.pending, lat, lon)
        core.update {
            it.copy(addStep = if (nearby.isEmpty()) AddStep.Form(lat, lon) else AddStep.CheckDuplicates(lat, lon, nearby))
        }
    }

    fun notADuplicate() {
        val step = core.value.addStep as? AddStep.CheckDuplicates ?: return
        core.update { it.copy(addStep = AddStep.Form(step.lat, step.lon)) }
    }

    /** "It's this one": open the existing place instead, so the user can add info to it. */
    fun pickExisting(place: Place) {
        core.update { it.copy(addStep = AddStep.None) }
        select(place)
    }

    fun sendNewPlace(lat: Double, lon: Double, report: Report) {
        val newPlace: NewPlace = report.newPlace ?: return
        val place = Place(
            id = Place.PENDING_PREFIX + java.util.UUID.randomUUID(),
            name = newPlace.name.trim(),
            lat = lat, lon = lon,
            category = newPlace.amenity,
            tags = mapOf("name" to newPlace.name.trim(), "amenity" to newPlace.amenity) + report.facilityTags(),
        )
        reportState.value = ReportState.Sending
        viewModelScope.launch {
            reportState.value = try {
                val note = deps.notes.createNote(place, report)
                store.addPending(place.copy(tags = place.tags + (Place.TAG_NOTE_URL to note.url)))
                store.addReport(
                    SubmittedReport(
                        noteId = note.id, noteUrl = note.url, placeId = place.id, placeName = place.name,
                        isNewPlace = true, createdAt = System.currentTimeMillis(), reportedTags = report.facilityTags(),
                    ),
                )
                ReportState.Sent(note.url)
            } catch (e: Exception) {
                ReportState.Failed(e.message ?: "Could not send.")
            }
        }
    }

    fun finishAdd() {
        core.update { it.copy(addStep = AddStep.None) }
        resetReport()
    }

    fun removePending(place: Place) {
        select(null)
        viewModelScope.launch { store.removePending(listOf(place.id)) }
    }

    /** Re-checks the status of the user's reports (rate-limited inside the tracker). */
    fun refreshReports() {
        viewModelScope.launch { runCatching { deps.reports.refresh() } }
    }

    fun resetReport() {
        reportState.value = ReportState.Idle
    }

    private fun BoundingBox.containsMostOf(other: BoundingBox): Boolean {
        val s = maxOf(south, other.south); val n = minOf(north, other.north)
        val w = maxOf(west, other.west); val e = minOf(east, other.east)
        if (s >= n || w >= e) return false
        return (n - s) * (e - w) >= 0.8 * other.areaDeg2
    }

    companion object {
        /** Roughly a 10 x 10 km box at Copenhagen's latitude — keeps Overpass queries fast and polite. */
        const val MAX_AREA_DEG2 = 0.016
        private const val MIN_FIT_AREA_DEG2 = 0.0002
        private const val CACHE_FRESH_MS = 12 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
