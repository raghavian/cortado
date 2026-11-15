package app.babylatte.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

@Serializable
data class OfflineIndex(val regions: List<RegionEntry>)

@Serializable
private data class RegionPlace(val id: String, val lat: Double, val lon: Double, val tags: Map<String, String>)

@Serializable
private data class RegionFile(val region: String, val generated: String, val places: List<RegionPlace>)

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
            for (entry in remote) {
                if (local[entry.id]?.generated == entry.generated && regionFile(entry.id).exists()) continue
                downloadRegion(entry)
                changed = true
            }
            // Drop regions that are no longer published.
            val stale = local.keys - remote.map { it.id }.toSet()
            stale.forEach { regionFile(it).delete() }
            if (changed || stale.isNotEmpty()) {
                writeMeta(remote)
                loadMutex.withLock { places = null } // reload on next query
            }
            _status.value = OfflineStatus(regions = remote)
            true
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

    private suspend fun loadPlaces(): List<Place>? = loadMutex.withLock {
        places?.let { return@withLock it }
        val ids = readMeta().map { it.id }
        if (ids.isEmpty()) return@withLock null
        withContext(Dispatchers.Default) {
            val loaded = ids.flatMap { id ->
                runCatching {
                    json.decodeFromString<RegionFile>(regionFile(id).readText()).places.map {
                        Place(it.id, it.tags["name"] ?: "", it.lat, it.lon, it.tags["amenity"] ?: "cafe", it.tags)
                    }
                }.getOrDefault(emptyList())
            }
            coveredCells = loaded.mapTo(HashSet()) { cell(it.lat, it.lon) }
            loaded
        }.also { places = it }
    }

    private fun regionFile(id: String) = File(dir, "$id.json")
    private val metaFile get() = File(dir, "_index.json")

    private fun readMeta(): List<RegionEntry> =
        runCatching { json.decodeFromString<OfflineIndex>(metaFile.readText()).regions }.getOrDefault(emptyList())
            .filter { regionFile(it.id).exists() }

    private fun writeMeta(regions: List<RegionEntry>) {
        dir.mkdirs()
        metaFile.writeText(json.encodeToString(OfflineIndex.serializer(), OfflineIndex(regions)))
    }

    companion object {
        private const val CELL_DEG = 0.1

        fun cell(lat: Double, lon: Double): Long =
            floor(lat / CELL_DEG).toLong() * 100_000L + floor(lon / CELL_DEG).toLong()

        fun parseInstant(iso: String): Long =
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)
    }
}
