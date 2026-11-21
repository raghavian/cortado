package app.babylatte.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class NoteStatus { OPEN, CLOSED, HIDDEN }

/** A report the user sent, kept on the phone so they can follow what happened to it. */
@Serializable
data class SubmittedReport(
    val noteId: Long,
    val noteUrl: String,
    val placeId: String,
    val placeName: String,
    val isNewPlace: Boolean,
    val createdAt: Long,
    /** The tags the user reported, e.g. changing_table=yes, used to spot when the map has them. */
    val reportedTags: Map<String, String> = emptyMap(),
    val status: NoteStatus = NoteStatus.OPEN,
    val closedAt: String? = null,
    val mapperComment: String? = null,
    /** The change is visible in the app's data, i.e. it's really on the map now. */
    val onMap: Boolean = false,
    val lastChecked: Long = 0,
)

data class NoteState(val status: NoteStatus, val closedAt: String?, val mapperComment: String?)

/** Keeps the status of the user's reports up to date (OSM note status + whether the data shows it). */
class ReportTracker(
    private val store: UserStore,
    private val notes: NotesClient,
    private val offline: OfflineData,
) {
    /** Refreshes reports that can still change. [minIntervalMs] avoids re-asking OSM too often. */
    suspend fun refresh(now: Long = System.currentTimeMillis(), minIntervalMs: Long = 10 * 60_000L) {
        val reports = store.reports.first()
        for (r in reports) {
            if (r.onMap || r.status == NoteStatus.HIDDEN) continue
            if (now - r.createdAt > MAX_TRACK_MS) continue
            if (now - r.lastChecked < minIntervalMs) continue

            var updated = r.copy(lastChecked = now)
            run { // closed notes too: a mapper (or you) can reopen them
                val state = runCatching { notes.fetchNote(r.noteId) }.getOrNull()
                if (state != null) {
                    updated = updated.copy(status = state.status, closedAt = state.closedAt, mapperComment = state.mapperComment)
                }
            }
            if (!r.isNewPlace && isOnMap(r, offline.findById(r.placeId))) updated = updated.copy(onMap = true)
            store.updateReport(updated)
        }
    }

    companion object {
        /** A report rebuilt from its OSM note, plus the dark pin to show if it's an added café still in review. */
        data class Imported(val report: SubmittedReport, val pending: Place?)

        private val TAG_LINE = Regex("""\(suggested tag ([^=\s()]+)=([^\s()]+)\)""")
        private val NEW_PLACE = Regex("""^Missing place: "(.*)" \(suggested tags amenity=([a-z_]+),""")
        private val EXISTING = Regex("""^Family-friendliness report for "(.*)" \(https?://[^\s/]+/(node|way|relation)/(\d+)\)""")

        /**
         * Rebuilds a report from `GET /api/0.6/notes/{id}.json`, for restoring "My reports" (for example
         * after reinstalling). Returns null for notes not written by this app.
         */
        fun parseImport(body: String, noteUrl: String): Imported? {
            val root = Json.parseToJsonElement(body).jsonObject
            val props = root["properties"]?.jsonObject ?: return null
            val id = props["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
            val text = props["comments"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
            if (!text.contains("#babylatte")) return null
            val coords = root["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() } ?: return null
            if (coords.size != 2) return null
            val (lon, lat) = coords
            val created = props["date_created"]?.jsonPrimitive?.contentOrNull?.let { utc(it) } ?: 0L
            val tags = TAG_LINE.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            val state = parseNote(body)
            val firstLine = text.lineSequence().first()

            NEW_PLACE.find(firstLine)?.let { m ->
                val name = m.groupValues[1]
                val placeId = Place.PENDING_PREFIX + "note-$id"
                val report = SubmittedReport(
                    noteId = id, noteUrl = noteUrl, placeId = placeId, placeName = name, isNewPlace = true,
                    createdAt = created, reportedTags = tags, status = state.status, closedAt = state.closedAt,
                    mapperComment = state.mapperComment,
                )
                val pending = if (state.status == NoteStatus.OPEN) Place(
                    id = placeId, name = name, lat = lat, lon = lon, category = m.groupValues[2],
                    tags = mapOf("name" to name, "amenity" to m.groupValues[2]) + tags + (Place.TAG_NOTE_URL to noteUrl),
                ) else null
                return Imported(report, pending)
            }
            EXISTING.find(firstLine)?.let { m ->
                return Imported(
                    SubmittedReport(
                        noteId = id, noteUrl = noteUrl, placeId = "${m.groupValues[2]}/${m.groupValues[3]}",
                        placeName = m.groupValues[1], isNewPlace = false, createdAt = created, reportedTags = tags,
                        status = state.status, closedAt = state.closedAt, mapperComment = state.mapperComment,
                    ),
                    null,
                )
            }
            return null
        }

        /** "2026-11-19 21:13:41 UTC" (OSM note dates) -> epoch millis. */
        fun utc(osmDate: String): Long? = runCatching {
            java.time.LocalDateTime.parse(osmDate.removeSuffix(" UTC").replace(' ', 'T'))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()

        private const val MAX_TRACK_MS = 365L * 24 * 60 * 60 * 1000

        /** A correction is on the map once the place's current tags contain everything reported. */
        fun isOnMap(report: SubmittedReport, current: Place?): Boolean =
            current != null && report.reportedTags.isNotEmpty() &&
                report.reportedTags.all { (k, v) -> current.tags[k] == v }

        /** Parses `GET /api/0.6/notes/{id}.json`. */
        fun parseNote(body: String): NoteState {
            val props = Json.parseToJsonElement(body).jsonObject["properties"]!!.jsonObject
            val status = when (props["status"]?.jsonPrimitive?.contentOrNull) {
                "closed" -> NoteStatus.CLOSED
                "hidden" -> NoteStatus.HIDDEN
                else -> NoteStatus.OPEN
            }
            // The newest comment from a mapper (anything after the opening text).
            val comment = props["comments"]?.jsonArray
                ?.map { it.jsonObject }
                ?.filter { it["action"]?.jsonPrimitive?.contentOrNull != "opened" }
                ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                ?.lastOrNull()
            return NoteState(status, props["closed_at"]?.jsonPrimitive?.contentOrNull, comment)
        }
    }
}
