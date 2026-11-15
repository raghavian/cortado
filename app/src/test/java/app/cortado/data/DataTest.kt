package app.cortado.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTest {

    private fun place(vararg tags: Pair<String, String>) =
        Place("node/1", "Test", 55.0, 12.0, "cafe", mapOf("name" to "Test", *tags))

    @Test fun parsesOverpassNodesAndWays() {
        val body = """
            {"elements":[
              {"type":"node","id":42,"lat":55.68,"lon":12.57,"tags":{"amenity":"cafe","name":"Bean","changing_table":"yes"}},
              {"type":"way","id":7,"center":{"lat":55.69,"lon":12.58},"tags":{"amenity":"restaurant","name":"Way Cafe"}},
              {"type":"node","id":3,"lat":1.0,"lon":2.0,"tags":{"amenity":"cafe"}}
            ]}
        """.trimIndent()
        val places = OverpassClient.parse(body)
        assertEquals(2, places.size)
        assertEquals("node/42", places[0].id)
        assertEquals(Status.YES, places[0].status(Feature.CHANGING_TABLE))
        assertEquals("way/7", places[1].id)
        assertEquals(55.69, places[1].lat, 1e-9)
        assertEquals("restaurant", places[1].category)
    }

    @Test fun tagValues() {
        assertEquals(Status.YES, OsmTags.yesNo("yes"))
        assertEquals(Status.YES, OsmTags.yesNo("3"))
        assertEquals(Status.NO, OsmTags.yesNo("0"))
        assertEquals(Status.LIMITED, OsmTags.yesNo("limited"))
        assertEquals(Status.UNKNOWN, OsmTags.yesNo(null))
        assertEquals(Status.UNKNOWN, OsmTags.yesNo("maybe"))
    }

    @Test fun pramAccessFallsBackToWheelchair() {
        assertEquals(Status.YES, place("wheelchair" to "yes").status(Feature.PRAM_ACCESS))
        assertEquals(Status.LIMITED, place("wheelchair" to "limited").status(Feature.PRAM_ACCESS))
        // explicit stroller tag wins
        assertEquals(Status.NO, place("stroller" to "no", "wheelchair" to "yes").status(Feature.PRAM_ACCESS))
        assertEquals(Status.YES, place("entrance:step_count" to "0").status(Feature.PRAM_ACCESS))
    }

    @Test fun legacyDiaperTag() {
        assertEquals(Status.YES, place("diaper" to "2").status(Feature.CHANGING_TABLE))
    }

    @Test fun babyFriendlyFilter() {
        val plain = place("wheelchair" to "yes")
        val baby = place("highchair" to "yes")
        assertFalse(plain.isBabyFriendly)
        assertTrue(baby.isBabyFriendly)
        assertTrue(Filters.matches(baby, setOf(Filters.BABY_FRIENDLY)))
        assertFalse(Filters.matches(plain, setOf(Filters.BABY_FRIENDLY)))
        assertTrue(Filters.matches(plain, emptySet()))
    }

    @Test fun filtersCombineWithAnd() {
        val p = place("changing_table" to "yes", "wheelchair" to "limited")
        assertTrue(Filters.matches(p, setOf(Feature.CHANGING_TABLE.name, Feature.PRAM_ACCESS.name)))
        assertFalse(Filters.matches(p, setOf(Feature.CHANGING_TABLE.name, Feature.HIGHCHAIR.name)))
    }

    @Test fun sortPutsFamilyFriendlyFirst() {
        val a = Place("node/1", "A", 55.0, 12.0, "cafe", mapOf("name" to "A"))
        val b = Place("node/2", "B", 55.1, 12.1, "cafe", mapOf("name" to "B", "changing_table" to "yes"))
        assertEquals(listOf("B", "A"), Filters.sort(listOf(a, b), 55.0, 12.0).map { it.name })
    }

    @Test fun noteTextListsOnlyAnsweredFeatures() {
        val text = NotesClient.noteText(
            place(),
            Report(mapOf(Feature.CHANGING_TABLE to Status.YES, Feature.HIGHCHAIR to Status.UNKNOWN), " downstairs "),
        )
        assertTrue(text.contains("changing_table=yes"))
        assertFalse(text.contains("highchair"))
        assertTrue(text.contains("Comment: downstairs"))
        assertTrue(text.contains("https://www.openstreetmap.org/node/1"))
    }

    @Test fun overpassQueryShape() {
        val q = OverpassClient.buildQuery(BoundingBox(55.6, 12.5, 55.7, 12.6), Category.CAFES)
        assertEquals(
            "[out:json][timeout:60];(nwr[\"amenity\"=\"cafe\"][\"name\"](55.6,12.5,55.7,12.6););out center tags qt 600;",
            q,
        )
    }

    @Test fun geocodingParse() {
        val r = GeocodingClient.parse(
            """[{"lat":"55.67","lon":"12.56","display_name":"København","boundingbox":["55.6","55.7","12.4","12.7"]}]""",
        )
        assertEquals(1, r.size)
        assertEquals(BoundingBox(55.6, 12.4, 55.7, 12.7), r[0].bbox)
    }
}
