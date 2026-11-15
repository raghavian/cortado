package app.cortado.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedArea(
    val south: Double, val west: Double, val north: Double, val east: Double,
    val category: Category,
    val fetchedAt: Long,
    val places: List<Place>,
) {
    val bbox: BoundingBox get() = BoundingBox(south, west, north, east)
}

/**
 * Keeps the most recently loaded areas on disk, so returning to a neighbourhood is instant and
 * works offline or when the public Overpass servers are overloaded.
 */
class PlaceCache(private val file: File, private val maxAreas: Int = 12) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var areas: MutableList<CachedArea>? = null

    /** Best cached area covering most of [box], or null. */
    suspend fun find(box: BoundingBox, category: Category): CachedArea? = mutex.withLock {
        load().filter { it.category == category && coverage(it.bbox, box) >= 0.9 }.maxByOrNull { it.fetchedAt }
    }

    suspend fun put(box: BoundingBox, category: Category, places: List<Place>, now: Long = System.currentTimeMillis()) =
        mutex.withLock {
            val list = load()
            list.removeAll { it.category == category && coverage(box, it.bbox) >= 0.9 } // superseded
            list.add(0, CachedArea(box.south, box.west, box.north, box.east, category, now, places))
            while (list.size > maxAreas) list.removeAt(list.lastIndex)
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CachedArea.serializer()), list)) }
            }
            Unit
        }

    private suspend fun load(): MutableList<CachedArea> = areas ?: withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CachedArea.serializer()), file.readText())
        }.getOrDefault(emptyList()).toMutableList()
    }.also { areas = it }

    companion object {
        /** Fraction of [inner]'s area that lies inside [outer]. */
        fun coverage(outer: BoundingBox, inner: BoundingBox): Double {
            val s = maxOf(outer.south, inner.south); val n = minOf(outer.north, inner.north)
            val w = maxOf(outer.west, inner.west); val e = minOf(outer.east, inner.east)
            if (s >= n || w >= e || inner.areaDeg2 <= 0) return 0.0
            return (n - s) * (e - w) / inner.areaDeg2
        }
    }
}
