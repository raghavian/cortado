package app.cortado.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NetworkException(message: String, cause: Throwable? = null, val httpCode: Int? = null) : IOException(message, cause)

/** Minimal HTTP helper: every OSM service requires an identifying User-Agent. */
class Http(private val userAgent: String) {

    suspend fun get(url: String): String = request(url, "GET", null).toString(Charsets.UTF_8)

    suspend fun getBytes(url: String): ByteArray = request(url, "GET", null)

    suspend fun postForm(url: String, form: Map<String, String>): String =
        request(url, "POST", form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })
            .toString(Charsets.UTF_8)

    private suspend fun request(url: String, method: String, body: String?): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 90_000 // public Overpass servers can take a minute when busy
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        // Blocking socket reads ignore coroutine cancellation, so abort the connection explicitly.
        // Otherwise abandoned requests keep running and use up our Overpass rate-limit slots.
        val abort = coroutineContext[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw NetworkException("HTTP $code ${err.take(200)}", httpCode = code)
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            throw e as? NetworkException ?: NetworkException("Network error: ${e.message}", e)
        } finally {
            abort?.dispose()
            conn.disconnect()
        }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    val areaDeg2: Double get() = (north - south) * (east - west)
}

enum class Category(val label: String, val osmAmenities: List<String>) {
    CAFES("Cafés", listOf("cafe")),
    CAFES_AND_FOOD("Cafés & restaurants", listOf("cafe", "restaurant", "ice_cream", "fast_food")),
}

/** Reads places from OpenStreetMap through the Overpass API. */
class OverpassClient(
    private val http: Http,
    private val endpoints: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    ),
) {
    suspend fun fetch(bbox: BoundingBox, category: Category): List<Place> {
        val query = buildQuery(bbox, category)
        var last: NetworkException? = null
        for (endpoint in endpoints) {
            var retried = false
            while (true) {
                try {
                    return parse(http.postForm(endpoint, mapOf("data" to query)))
                } catch (e: NetworkException) {
                    last = e
                    // 429 = too many requests, 504 = server overloaded: wait briefly and retry once.
                    if (!retried && (e.httpCode == 429 || e.httpCode == 504)) {
                        retried = true
                        delay(4_000)
                    } else break
                }
            }
        }
        val busy = last?.httpCode in setOf(429, 502, 503, 504) || last?.cause is java.net.SocketTimeoutException
        throw NetworkException(
            if (busy) "OpenStreetMap servers are busy right now. Please try again in a minute."
            else "Could not reach OpenStreetMap. Check your connection and try again.",
            last,
        )
    }

    companion object {
        const val MAX_RESULTS = 600

        fun buildQuery(b: BoundingBox, category: Category): String {
            val box = "${b.south},${b.west},${b.north},${b.east}"
            // Exact-match unions are much cheaper for Overpass than a regex.
            val parts = category.osmAmenities.joinToString("") { "nwr[\"amenity\"=\"$it\"][\"name\"]($box);" }
            return "[out:json][timeout:60];($parts);out center tags qt $MAX_RESULTS;"
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): List<Place> {
            val elements = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return emptyList()
            return elements.mapNotNull { el ->
                val o = el.jsonObject
                val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val center = o["center"]?.jsonObject ?: o
                val lat = center["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lon = center["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val name = tags["name"] ?: return@mapNotNull null
                Place("$type/$id", name, lat, lon, tags["amenity"] ?: "cafe", tags)
            }
        }
    }
}

data class GeoResult(val displayName: String, val lat: Double, val lon: Double, val bbox: BoundingBox?)

/** City / address search via Nominatim. Called only on explicit submit (usage policy: no autocomplete). */
class GeocodingClient(private val http: Http) {
    suspend fun search(query: String): List<GeoResult> {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=${Http.enc(query)}"
        return parse(http.get(url))
    }

    companion object {
        fun parse(body: String): List<GeoResult> =
            Json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                val bb = o["boundingbox"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }
                GeoResult(
                    o["display_name"]?.jsonPrimitive?.contentOrNull ?: "$lat, $lon",
                    lat, lon,
                    bb?.takeIf { it.size == 4 }?.let { BoundingBox(it[0], it[2], it[1], it[3]) },
                )
            }
    }
}

/**
 * Sends user reports as anonymous OpenStreetMap Notes. OSM volunteers review notes and
 * update the map, so the data improves for every app built on OSM.
 */
class NotesClient(private val http: Http, private val apiBase: String) {

    /** Returns the URL of the created note. */
    suspend fun createNote(place: Place, report: Report): String {
        val body = http.postForm(
            "$apiBase/notes.json",
            mapOf("lat" to place.lat.toString(), "lon" to place.lon.toString(), "text" to noteText(place, report)),
        )
        val id = Json.parseToJsonElement(body).jsonObject["properties"]?.jsonObject?.get("id")
            ?.jsonPrimitive?.longOrNull
        val site = apiBase.removeSuffix("/api/0.6").replace("api.openstreetmap.org", "www.openstreetmap.org")
        return if (id != null) "$site/note/$id" else site
    }

    companion object {
        fun noteText(place: Place, report: Report): String = buildString {
            appendLine("Family-friendliness report for \"${place.name}\" (${place.osmUrl}):")
            report.answers.forEach { (feature, status) ->
                val value = when (status) {
                    Status.YES -> "yes"
                    Status.LIMITED -> "limited"
                    Status.NO -> "no"
                    Status.UNKNOWN -> return@forEach
                }
                appendLine("- ${feature.label}: $value (suggested tag ${OsmTags.reportTag(feature)}=$value)")
            }
            if (report.comment.isNotBlank()) {
                appendLine()
                appendLine("Comment: ${report.comment.trim()}")
            }
            appendLine()
            append("Reported by a visitor via the Cortado app. #cortadoapp")
        }
    }
}

data class Report(val answers: Map<Feature, Status>, val comment: String) {
    val isEmpty: Boolean get() = answers.values.all { it == Status.UNKNOWN } && comment.isBlank()
}
