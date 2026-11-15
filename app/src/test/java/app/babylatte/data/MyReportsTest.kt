package app.babylatte.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyReportsTest {
    // Shapes copied from real api.openstreetmap.org responses.
    private val closedNote = """
        {"type":"Feature","geometry":{"type":"Point","coordinates":[5.7,45.1]},
         "properties":{"id":5634321,"status":"closed","date_created":"2026-11-14 10:00:00 UTC",
          "closed_at":"2026-11-15 20:46:36 UTC",
          "comments":[
            {"date":"2026-11-14 10:00:00 UTC","action":"opened","text":"La rampe est très pentue"},
            {"date":"2026-11-15 20:46:36 UTC","uid":1,"user":"mapper","action":"closed","text":"changed it to \"no\""}
          ]}}
    """.trimIndent()

    private val reopenedNote = """
        {"type":"Feature","properties":{"id":5625063,"status":"open",
          "comments":[
            {"action":"opened","text":"Missing café"},
            {"action":"commented","text":"What change are you seeking?"},
            {"action":"closed","text":"Added it"},
            {"action":"reopened","text":""}
          ]}}
    """.trimIndent()

    @Test fun parsesClosedNoteWithMapperComment() {
        val s = ReportTracker.parseNote(closedNote)
        assertEquals(NoteStatus.CLOSED, s.status)
        assertEquals("2026-11-15 20:46:36 UTC", s.closedAt)
        assertEquals("changed it to \"no\"", s.mapperComment)
    }

    @Test fun reopenedNoteIsOpenAndKeepsLastRealComment() {
        val s = ReportTracker.parseNote(reopenedNote)
        assertEquals(NoteStatus.OPEN, s.status)
        assertNull(s.closedAt)
        assertEquals("Added it", s.mapperComment) // empty "reopened" text is skipped
    }

    @Test fun openNoteWithoutReplies() {
        val s = ReportTracker.parseNote("""{"properties":{"status":"open","comments":[{"action":"opened","text":"hi"}]}}""")
        assertEquals(NoteStatus.OPEN, s.status)
        assertNull(s.mapperComment)
    }

    private fun report(tags: Map<String, String>) = SubmittedReport(
        noteId = 1, noteUrl = "u", placeId = "node/7", placeName = "Kaf", isNewPlace = false,
        createdAt = 0, reportedTags = tags,
    )
    private fun place(vararg tags: Pair<String, String>) = Place("node/7", "Kaf", 55.0, 12.0, "cafe", mapOf(*tags))

    @Test fun correctionIsOnMapOnlyWhenAllReportedTagsArePresent() {
        val r = report(mapOf("changing_table" to "yes", "highchair" to "yes"))
        assertFalse(ReportTracker.isOnMap(r, place("changing_table" to "yes")))
        assertTrue(ReportTracker.isOnMap(r, place("changing_table" to "yes", "highchair" to "yes", "name" to "Kaf")))
        assertFalse(ReportTracker.isOnMap(r, place("changing_table" to "no", "highchair" to "yes")))
        assertFalse(ReportTracker.isOnMap(r, null))
        // A comment-only report can't be verified from the data.
        assertFalse(ReportTracker.isOnMap(report(emptyMap()), place("name" to "Kaf")))
    }
}
