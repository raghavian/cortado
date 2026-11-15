package app.babylatte.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real Overpass/Nominatim servers. Run with: ./gradlew testDebugUnitTest -Plive */
class LiveApiTest {
    private val http = Http("babylatte-tests/1.0")

    @Test fun overpassCopenhagen() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val places = OverpassClient(http).fetch(BoundingBox(55.670, 12.555, 55.695, 12.600), Category.CAFES)
        println("Overpass: ${places.size} cafés, ${places.count { it.isBabyFriendly }} baby friendly, " +
            "${places.count { it.status(Feature.PRAM_ACCESS) == Status.YES }} pram access")
        places.filter { it.isBabyFriendly }.take(5).forEach { p ->
            println("  ${p.name}: " + Feature.entries.filter { p.status(it) == Status.YES }.joinToString { it.label })
        }
        assertTrue(places.size > 50)
    }

    @Test fun noteStatusFromRealOsm() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val s = NotesClient(http, "https://api.openstreetmap.org/api/0.6").fetchNote(5634321)
        println("Note: $s")
        assertTrue(s.status == NoteStatus.CLOSED && s.mapperComment != null)
    }

    @Test fun nominatimSearch() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val r = GeocodingClient(http).search("Nørrebro, København")
        println("Nominatim: ${r.firstOrNull()}")
        assertTrue(r.isNotEmpty())
    }
}
