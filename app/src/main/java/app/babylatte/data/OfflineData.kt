package app.babylatte.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.floor

@Serializable
data class RegionEntry(
    val id: String,
    val name: String,
    val url: String,
    val bytes: Long = 0,
    val placeCount: Int = 0,
    val generated: String,
    /** Offline search index (towns, neighbourhoods, streets): a separate file, rebuilt weekly. */
    val searchUrl: String? = null,
    val searchBytes: Long = 0,
    val searchGenerated: String? = null,
    /** Time of the newest OpenStreetMap edit included (Geofabrik's extract time). */
    val osmDataUntil: String? = null,
)

@Serializable
data class OfflineIndex(val regions: List<RegionEntry>)

@Serializable
private data class RegionPlace(val id: String, val lat: Double, val lon: Double, val tags: Map<String, String>)

/** A town, neighbourhood or street from the region file, for searching areas without the internet. */
@Serializable
data class SearchEntry(
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    @SerialName("in") val context: String? = null,
    val bbox: List<Double>? = null,
)

@Serializable
private data class RegionFile(val region: String, val generated: String, val places: List<RegionPlace>)

@Serializable
private data class SearchFile(val region: String, val generated: String, val search: List<SearchEntry>)

/** A search entry with its name pre-normalized once, so each search is a quick scan. */
private class IndexedEntry(val entry: SearchEntry, val full: String, val words: List<String>)

/** What the user sees about offline data (shown in About and while downloading). */
data class OfflineStatus(
    val regions: List<RegionEntry> = emptyList(),
    val syncing: Boolean = false,
    val lastError: String? = null,
) {
    val placeCount: Int get() = regions.sumOf { it.placeCount }
}

/**
 * Whole-country café data stored on the phone, so search and filters work instantly and offline.
 * The files are published daily by `.github/workflows/data.yml` and refreshed by [SyncWorker].
 * Areas outside the downloaded regions fall back to live Overpass.
 */
class OfflineData(private val dir: File, private val http: Http, private val baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()
    private val loadMutex = Mutex()

    private var places: List<Place>? = null
    /** Loaded on the first area search, not at startup, so the map's pins appear without waiting for it. */
    private var searchIndex: List<IndexedEntry>? = null
    private val searchMutex = Mutex()
    /** 0.1° grid cells that contain at least one offline place, used to decide coverage. */
    private var coveredCells: Set<Long> = emptySet()

    private val _status = MutableStateFlow(OfflineStatus(regions = readMeta()))
    val status: StateFlow<OfflineStatus> = _status.asStateFlow()

    val hasData: Boolean get() = _status.value.regions.isNotEmpty()

    /**
     * Places in [box] from offline data, or null if the area isn't covered (or nothing is
     * downloaded yet), so the caller can use Overpass instead.
     */
    suspend fun query(box: BoundingBox, category: Category): List<Place>? {
        val all = loadPlaces() ?: return null
        val cLat = (box.south + box.north) / 2
        val cLon = (box.west + box.east) / 2
        if (cell(cLat, cLon) !in coveredCells) return null
        return all.filter {
            it.lat in box.south..box.north && it.lon in box.west..box.east && it.category in category.osmAmenities
        }
    }

    /** A single offline place by OSM id (e.g. "node/123"), or null. */
    suspend fun findById(id: String): Place? = loadPlaces()?.firstOrNull { it.id == id }

    /** All offline places whose name contains [query] (accent- and case-insensitive). */
    suspend fun searchByName(query: String): List<Place> {
        val q = Pending.normalize(query)
        if (q.length < 2) return emptyList()
        val all = loadPlaces() ?: return emptyList()
        return withContext(Dispatchers.Default) { all.filter { Pending.normalize(it.name).contains(q) } }
    }

    /**
     * Towns, neighbourhoods and streets matching [query], best first, from the offline data. Matches the
     * whole name or the start of any word in it ("nørrebro" finds Nørrebro and Nørrebrogade). House
     * numbers and anything after a comma are ignored, so "Jægersborggade 10, 2200" finds the street.
     */
    suspend fun searchAreas(query: String, refLat: Double, refLon: Double, limit: Int = 8): List<GeoResult> {
        val q = Pending.normalize(query.substringBefore(',').split(' ').filterNot { it.any(Char::isDigit) }.joinToString(" "))
        if (q.length < 2) return emptyList()
        val index = loadSearch()
        if (index.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            index.mapNotNull { e ->
                val score = when {
                    e.full == q -> 0
                    e.full.startsWith(q) -> 1
                    e.words.any { it.startsWith(q) } -> 2
                    else -> return@mapNotNull null
                }
                Triple(e, score, Filters.distanceMeters(refLat, refLon, e.entry.lat, e.entry.lon))
            }
                .sortedWith(compareBy({ it.second }, { kindRank(it.first.entry.kind) }, { it.third }))
                .map { toGeoResult(it.first.entry) }
                .distinctBy { it.displayName }
                .take(limit)
        }
    }

    /** When the app last successfully asked whether newer data is published (0 if never). */
    fun lastCheckedMillis(): Long = checkedFile.lastModified()

    /** Hours since the oldest region was generated, or null if nothing is downloaded. */
    fun ageMillis(now: Long = System.currentTimeMillis()): Long? =
        _status.value.regions.minOfOrNull { parseInstant(it.generated) }?.let { now - it }

    /**
     * Downloads any region whose published version is newer than ours. Safe to call often:
     * it only fetches the small index unless something changed. Returns true on success.
     */
    suspend fun sync(): Boolean = syncMutex.withLock {
        _status.value = _status.value.copy(syncing = true, lastError = null)
        try {
            val remote = json.decodeFromString<OfflineIndex>(http.get("$baseUrl/index.json")).regions
            val local = readMeta().associateBy { it.id }
            var changed = false
            var searchError: String? = null
            val saved = remote.map { entry ->
                val mine = local[entry.id]
                if (mine?.generated != entry.generated || !regionFile(entry.id).exists()) {
                    downloadRegion(entry)
                    changed = true
                }
                // The search file changes about weekly; fetch it only when it did. If it fails, keep the
                // old one: place data matters more and is already saved.
                val haveSearch = searchFile(entry.id).exists()
                val keepOld = entry.copy(
                    searchUrl = mine?.searchUrl.takeIf { haveSearch },
                    searchBytes = if (haveSearch) mine?.searchBytes ?: 0 else 0,
                    searchGenerated = mine?.searchGenerated.takeIf { haveSearch },
                )
                if (entry.searchUrl == null || (haveSearch && mine?.searchGenerated == entry.searchGenerated)) keepOld
                else try {
                    downloadSearch(entry.id, entry.searchUrl)
                    changed = true
                    entry
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    searchError = e.message
                    keepOld
                }
            }
            // Drop regions that are no longer published.
            val stale = local.keys - remote.map { it.id }.toSet()
            stale.forEach { regionFile(it).delete(); searchFile(it).delete() }
            if (changed || stale.isNotEmpty() || saved != local.values.toList()) {
                writeMeta(saved)
                loadMutex.withLock { places = null } // reload on next query
                searchMutex.withLock { searchIndex = null }
            }
            dir.mkdirs()
            checkedFile.writeText(System.currentTimeMillis().toString()) // its modification time is what counts
            _status.value = OfflineStatus(regions = saved, lastError = searchError)
            searchError == null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(syncing = false, lastError = e.message)
            false
        }
    }

    private suspend fun downloadRegion(entry: RegionEntry) = withContext(Dispatchers.IO) {
        val bytes = http.getBytes("$baseUrl/${entry.url}")
        val text = GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
        val parsed = json.decodeFromString<RegionFile>(text) // validate before replacing the old file
        require(parsed.places.isNotEmpty()) { "Downloaded region ${entry.id} is empty" }
        dir.mkdirs()
        val tmp = File(dir, "${entry.id}.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(regionFile(entry.id))) {
            regionFile(entry.id).delete()
            check(tmp.renameTo(regionFile(entry.id))) { "Could not save offline data" }
        }
    }

    private suspend fun downloadSearch(id: String, url: String) = withContext(Dispatchers.IO) {
        val bytes = http.getBytes("$baseUrl/$url")
        val text = GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
        json.decodeFromString<SearchFile>(text) // validate before replacing the old file
        dir.mkdirs()
        val tmp = File(dir, "$id.search.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(searchFile(id))) {
            searchFile(id).delete()
            check(tmp.renameTo(searchFile(id))) { "Could not save offline search data" }
        }
    }

    private suspend fun loadPlaces(): List<Place>? = loadMutex.withLock {
        places?.let { return@withLock it }
        val ids = readMeta().map { it.id }
        if (ids.isEmpty()) return@withLock null
        withContext(Dispatchers.Default) {
            val files = ids.mapNotNull { id ->
                runCatching { json.decodeFromString<RegionFile>(regionFile(id).readText()) }.getOrNull()
            }
            val loaded = files.flatMap { file ->
                file.places.map {
                    Place(it.id, it.tags["name"] ?: "", it.lat, it.lon, it.tags["amenity"] ?: "cafe", it.tags)
                }
            }
            coveredCells = loaded.mapTo(HashSet()) { cell(it.lat, it.lon) }
            loaded
        }.also { places = it }
    }

    private suspend fun loadSearch(): List<IndexedEntry> = searchMutex.withLock {
        searchIndex?.let { return@withLock it }
        withContext(Dispatchers.Default) {
            readMeta().flatMap { region ->
                runCatching { json.decodeFromString<SearchFile>(searchFile(region.id).readText()).search }
                    .getOrDefault(emptyList())
            }.map { e ->
                IndexedEntry(e, Pending.normalize(e.name), e.name.split(' ', '-', '/').map(Pending::normalize).filter { it.isNotEmpty() })
            }
        }.also { searchIndex = it }
    }

    private fun regionFile(id: String) = File(dir, "$id.json")
    private fun searchFile(id: String) = File(dir, "$id.search.json")
    private val metaFile get() = File(dir, "_index.json")
    private val checkedFile get() = File(dir, "_checked")

    private fun readMeta(): List<RegionEntry> =
        runCatching { json.decodeFromString<OfflineIndex>(metaFile.readText()).regions }.getOrDefault(emptyList())
            .filter { regionFile(it.id).exists() }

    private fun writeMeta(regions: List<RegionEntry>) {
        dir.mkdirs()
        metaFile.writeText(json.encodeToString(OfflineIndex.serializer(), OfflineIndex(regions)))
    }

    companion object {
        private const val CELL_DEG = 0.1

        private val KIND_ORDER = listOf("city", "town", "suburb", "quarter", "village", "neighbourhood", "island",
            "street", "square", "hamlet")

        fun kindRank(kind: String): Int = KIND_ORDER.indexOf(kind).let { if (it < 0) KIND_ORDER.size else it }

        private fun kindLabel(kind: String) = when (kind) {
            "hamlet" -> "village"
            "suburb", "quarter", "neighbourhood" -> "area"
            else -> kind
        }

        private fun zoomFor(kind: String) = when (kind) {
            "city", "island" -> 13.0
            "town", "suburb" -> 14.0
            "street" -> 16.0
            "square" -> 17.0
            else -> 15.0
        }

        fun toGeoResult(e: SearchEntry): GeoResult = GeoResult(
            displayName = buildString {
                append(e.name).append(" \u2014 ").append(kindLabel(e.kind))
                e.context?.let { append(", ").append(it) }
            },
            lat = e.lat,
            lon = e.lon,
            bbox = e.bbox?.takeIf { it.size == 4 }?.let { BoundingBox(it[0], it[1], it[2], it[3]) },
            zoom = zoomFor(e.kind),
        )

        fun cell(lat: Double, lon: Double): Long =
            floor(lat / CELL_DEG).toLong() * 100_000L + floor(lon / CELL_DEG).toLong()

        fun parseInstant(iso: String): Long =
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)
    }
}
