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
            if (r.status == NoteStatus.OPEN) {
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
