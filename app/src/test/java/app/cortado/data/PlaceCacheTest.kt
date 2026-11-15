package app.cortado.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlaceCacheTest {
    private val box = BoundingBox(55.67, 12.55, 55.69, 12.60)
    private val place = Place("node/1", "A", 55.68, 12.57, "cafe", mapOf("name" to "A", "highchair" to "yes"))

    @Test fun roundTripsThroughDisk() = runBlocking {
        val f = File.createTempFile("cache", ".json").apply { delete() }
        PlaceCache(f).put(box, Category.CAFES, listOf(place))
        val hit = PlaceCache(f).find(BoundingBox(55.675, 12.56, 55.685, 12.59), Category.CAFES) // inside
        assertNotNull(hit)
        assertEquals(listOf(place), hit!!.places)
        assertEquals(Status.YES, hit.places[0].status(Feature.HIGHCHAIR))
        assertNull(PlaceCache(f).find(box, Category.CAFES_AND_FOOD)) // other category
        assertNull(PlaceCache(f).find(BoundingBox(55.70, 12.55, 55.72, 12.60), Category.CAFES)) // elsewhere
    }

    @Test fun coverage() {
        assertEquals(1.0, PlaceCache.coverage(box, box), 1e-9)
        assertEquals(0.5, PlaceCache.coverage(box, BoundingBox(55.68, 12.55, 55.70, 12.60)), 1e-9)
    }
}
