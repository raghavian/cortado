package app.cortado.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cortado.CortadoApp
import app.cortado.data.BoundingBox
import app.cortado.data.Category
import app.cortado.data.Filters
import app.cortado.data.GeoResult
import app.cortado.data.Place
import app.cortado.data.Report
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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val deps = app as CortadoApp
    private val store = deps.store

    private val core = MutableStateFlow(UiState())
    private var lastBox: BoundingBox? = null
    private var currentBox: BoundingBox? = null
    private var loadJob: Job? = null

    private val _commands = Channel<MapCommand>(Channel.BUFFERED)
    val commands = _commands.receiveAsFlow()

    val reportState = MutableStateFlow<ReportState>(ReportState.Idle)

    val state: StateFlow<UiState> = combine(core, store.favorites) { s, favs ->
        val visible = Filters.sort(s.places.filter { Filters.matches(it, s.filters) }, s.userLat, s.userLon)
        s.copy(favorites = favs, visible = visible)
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
    }

    /** Called by the map whenever the visible area settles. */
    fun onViewportChanged(box: BoundingBox, centerLat: Double, centerLon: Double, zoom: Double) {
        currentBox = box
        viewModelScope.launch { store.setCamera(centerLat, centerLon, zoom) }
        val last = lastBox
        if (last == null) {
            loadArea()
        } else {
            val moved = !last.containsMostOf(box)
            core.update { it.copy(areaChanged = moved) }
        }
    }

    fun loadArea() {
        val box = currentBox ?: return
        if (box.areaDeg2 > MAX_AREA_DEG2) {
            core.update { it.copy(info = "Zoom in a little to search for cafés here.", areaChanged = true) }
            return
        }
        loadJob?.cancel()
        val category = core.value.category
        loadJob = viewModelScope.launch {
            core.update { it.copy(error = null, info = null, areaChanged = false, slow = false) }

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
        _commands.trySend(MapCommand.MoveTo(place.lat, place.lon, 17.5))
        select(place)
    }

    fun dismissMessage() = core.update { it.copy(error = null, info = null) }

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            try {
                val results = deps.geocoding.search(query.trim())
                when {
                    results.isEmpty() -> core.update { it.copy(info = "No place found for \"$query\".") }
                    results.size == 1 -> goTo(results.first())
                    else -> core.update { it.copy(searchResults = results) }
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
        else _commands.trySend(MapCommand.MoveTo(result.lat, result.lon, 15.0))
        lastBox = null // force a fresh load once the map settles
    }

    fun clearSearchResults() = core.update { it.copy(searchResults = emptyList()) }

    fun sendReport(place: Place, report: Report) {
        reportState.value = ReportState.Sending
        viewModelScope.launch {
            reportState.value = try {
                ReportState.Sent(deps.notes.createNote(place, report))
            } catch (e: Exception) {
                ReportState.Failed(e.message ?: "Could not send the report.")
            }
        }
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
    }
}
