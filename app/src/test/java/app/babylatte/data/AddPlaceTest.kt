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
}
