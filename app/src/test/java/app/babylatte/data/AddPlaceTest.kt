package app.babylatte.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddPlaceTest {
    private fun real(id: String, name: String, lat: Double, lon: Double) =
        Place("node/$id", name, lat, lon, "cafe", mapOf("name" to name))
    private fun pending(name: String, lat: Double, lon: Double) =
        Place(Place.PENDING_PREFIX + name, name, lat, lon, "cafe", mapOf("name" to name))

    @Test fun pendingResolvesWhenOfficialPlaceAppearsNearby() {
        val mine = pending("Café Børnehjørnet", 55.6800, 12.5700)
        // ~30 m away, name differs only in accents/case/punctuation
        val official = real("9", "Cafe Bornehjornet", 55.68027, 12.5700)
        assertEquals(setOf(mine.id), Pending.resolved(listOf(mine), listOf(official)))
    }

    @Test fun pendingStaysIfOnlyFarOrDifferentName() {
        val mine = pending("Kaffebaren", 55.6800, 12.5700)
        val far = real("1", "Kaffebaren", 55.6900, 12.5700)          // ~1.1 km
        val other = real("2", "Pizza Napoli", 55.68001, 12.57001)     // same spot, other name
        assertTrue(Pending.resolved(listOf(mine), listOf(far, other)).isEmpty())
    }

    @Test fun nearbyFindsCloseOnesSortedByDistance() {
        val a = real("1", "A", 55.68020, 12.5700) // ~22 m
        val b = real("2", "B", 55.68005, 12.5700) // ~6 m
        val c = real("3", "C", 55.68100, 12.5700) // ~111 m
        assertEquals(listOf("B", "A"), Pending.nearby(listOf(a, b, c), 55.6800, 12.5700).map { it.name })
    }

    @Test fun normalizeFoldsDanishLettersAndAccents() {
        assertEquals("cafebornehjornet", Pending.normalize("Café Børne-Hjørnet!"))
        assertEquals(Pending.normalize("København"), Pending.normalize("kobenhavn"))
        assertEquals("aeblehaven", Pending.normalize("Æblehaven"))
    }

    @Test fun newPlaceNoteAndTags() {
        val report = Report(
            mapOf(Feature.CHANGING_TABLE to Status.YES, Feature.PRAM_ACCESS to Status.LIMITED, Feature.HIGHCHAIR to Status.UNKNOWN),
            "Entrance on the corner",
            NewPlace("Lille Kop", "cafe"),
        )
        val text = NotesClient.noteText(pending("Lille Kop", 55.68, 12.57), report)
        assertTrue(text.startsWith("Missing place: \"Lille Kop\" (suggested tags amenity=cafe, name=Lille Kop)"))
        assertTrue(text.contains("changing_table=yes"))
        assertTrue(text.contains("stroller=limited"))
        assertFalse(text.contains("highchair"))
        assertFalse(text.contains("openstreetmap.org/local")) // no bogus OSM link for a new place
        assertEquals(mapOf("changing_table" to "yes", "stroller" to "limited"), report.facilityTags())

        // Facility tags make the pending pin work with the filters.
        val p = Place("local/x", "Lille Kop", 55.68, 12.57, "cafe", mapOf("name" to "Lille Kop") + report.facilityTags())
        assertTrue(p.isPending)
        assertTrue(Filters.matches(p, setOf(Feature.CHANGING_TABLE.name, Filters.BABY_FRIENDLY)))
    }

    @Test fun genericOrPreExistingNamesDoNotRemoveThePin() {
        // A short generic name inside a longer one is not a match ("Cafe" next door used to remove "Cafe Lune").
        val lune = pending("Cafe Lune", 55.6800, 12.5700)
        assertTrue(Pending.resolved(listOf(lune), listOf(real("1", "Café", 55.68010, 12.5700))).isEmpty())

        // A similarly named place that was already there when the user added theirs never resolves it...
        val kop = pending("Lille Kop", 55.6800, 12.5700)
        val nextDoor = real("2", "Café Lille Kop", 55.68020, 12.5700)
        val existing = Pending.existingIds(listOf(nextDoor), kop.lat, kop.lon)
        val mine = kop.copy(tags = kop.tags + (Place.TAG_EXISTING to existing))
        assertEquals(setOf("node/2"), mine.existingNearby)
        assertTrue(Pending.resolved(listOf(mine), listOf(nextDoor)).isEmpty())
        // ...but a new one that mappers add later does, including a "Café" prefix.
        val added = real("3", "Café Lille Kop", 55.68005, 12.5700)
        assertEquals(setOf(mine.id), Pending.resolved(listOf(mine), listOf(nextDoor, added)))
    }

    @Test fun namesMatchRules() {
        val n = Pending::normalize
        assertTrue(Pending.namesMatch(n("Kaffebaren"), n("kaffebaren")))
        assertTrue(Pending.namesMatch(n("Lille Kop"), n("Café Lille Kop")))
        assertFalse(Pending.namesMatch(n("Cafe"), n("Cafe Lune")))           // too short
        assertFalse(Pending.namesMatch(n("Bageri"), n("Lagkagehuset Bageri og Café"))) // small part of a long name
        assertFalse(Pending.namesMatch("", n("x")))
    }

    @Test fun ownPendingPinsAreAlwaysShownFirst() {
        val mine = pending("Lille Kop", 55.68, 12.57) // no facility info, so it fails every filter
        assertFalse(Filters.matches(mine, setOf(Filters.BABY_FRIENDLY)))
        val shown = Pending.withPending(listOf(real("1", "A", 55.68, 12.57)), listOf(mine))
        assertEquals(listOf(mine.id, "node/1"), shown.map { it.id })
    }
}
