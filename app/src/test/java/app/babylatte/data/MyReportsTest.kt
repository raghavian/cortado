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

    private fun myReport(placeId: String, tags: Map<String, String>, status: NoteStatus = NoteStatus.OPEN,
                         closedAt: String? = null, onMap: Boolean = false, createdAt: Long = 1) =
        SubmittedReport(noteId = createdAt, noteUrl = "https://www.openstreetmap.org/note/$createdAt", placeId = placeId,
            placeName = "Espresso House", isNewPlace = false, createdAt = createdAt, reportedTags = tags,
            status = status, closedAt = closedAt, onMap = onMap)

    @Test fun yourReportsShowRightAwayUntilTheMapHasThem() {
        val eh = Place("node/8269993820", "Espresso House", 55.6957, 12.5660, "cafe", mapOf("name" to "Espresso House"))
        val other = Place("node/2", "Other", 55.69, 12.56, "cafe", mapOf("name" to "Other"))
        val now = java.time.Instant.parse("2026-11-21T20:00:00Z").toEpochMilli()

        val open = myReport(eh.id, mapOf("changing_table" to "yes", "kids_area" to "yes"))
        val shown = Pending.applyMyReports(listOf(eh, other), listOf(open), now)
        assertEquals(Status.YES, shown[0].status(Feature.CHANGING_TABLE))
        assertTrue(shown[0].isMine)
        assertEquals("https://www.openstreetmap.org/note/1", shown[0].myReportNoteUrl)
        assertFalse(shown[1].isMine)
        assertTrue(Filters.matches(shown[0], setOf(Feature.CHANGING_TABLE.name))) // filters see your answers too

        // A later report overrides an earlier one.
        val later = myReport(eh.id, mapOf("changing_table" to "no"), createdAt = 2)
        assertEquals(Status.NO, Pending.applyMyReports(listOf(eh), listOf(later, open), now)[0].status(Feature.CHANGING_TABLE))

        // Gone once it's on the map, hidden, or closed more than 3 days ago; kept for a closed one meanwhile.
        assertFalse(Pending.applyMyReports(listOf(eh), listOf(open.copy(onMap = true)), now)[0].isMine)
        assertFalse(Pending.applyMyReports(listOf(eh), listOf(open.copy(status = NoteStatus.HIDDEN)), now)[0].isMine)
        assertTrue(Pending.applyMyReports(listOf(eh), listOf(open.copy(status = NoteStatus.CLOSED, closedAt = "2026-11-20 08:00:00 UTC")), now)[0].isMine)
        assertFalse(Pending.applyMyReports(listOf(eh), listOf(open.copy(status = NoteStatus.CLOSED, closedAt = "2026-11-15 08:00:00 UTC")), now)[0].isMine)

        // Your places come first so the pin limit never drops them.
        assertEquals(listOf(eh.id, other.id), Pending.withPending(Pending.applyMyReports(listOf(other, eh), listOf(open), now), emptyList()).map { it.id })
    }

    private fun note(id: Long) = javaClass.getResource("/note-$id.json")!!.readText()

    @Test fun restoresReportsFromRealNotes() {
        // Real notes from api.openstreetmap.org (saved in test resources): an added café...
        val added = ReportTracker.parseImport(note(5640650), "https://www.openstreetmap.org/note/5640650")!!
        assertTrue(added.report.isNewPlace)
        assertEquals("Espresso House", added.report.placeName)
        assertEquals(NoteStatus.OPEN, added.report.status)
        val pin = added.pending!!
        assertTrue(pin.isPending)
        assertEquals(55.7260462, pin.lat, 1e-7)
        assertEquals(12.5803097, pin.lon, 1e-7)
        assertEquals("cafe", pin.tags["amenity"])
        assertEquals("yes", pin.tags["stroller"])
        assertEquals("no", pin.tags["outdoor_seating"])
        assertEquals("https://www.openstreetmap.org/note/5640650", pin.pendingNoteUrl)
        assertEquals(ReportTracker.utc("2026-11-19 21:13:41 UTC"), added.report.createdAt)

        // ...and a report about an existing one, which then shows with your answers.
        val report = ReportTracker.parseImport(note(5640428), "https://www.openstreetmap.org/note/5640428")!!
        assertFalse(report.report.isNewPlace)
        assertNull(report.pending)
        assertEquals("node/8269993820", report.report.placeId)
        assertEquals("yes", report.report.reportedTags["changing_table"])
        assertEquals("yes", report.report.reportedTags["kids_area"])
        val eh = Place("node/8269993820", "Espresso House", 55.6957, 12.566, "cafe", mapOf("name" to "Espresso House"))
        assertTrue(Pending.applyMyReports(listOf(eh), listOf(report.report))[0].isMine)

        // Notes not written by the app are ignored.
        assertNull(ReportTracker.parseImport(note(5640428).replace("#babylatte", ""), "x"))
    }
}
