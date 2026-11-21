package app.babylatte.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Serves files shaped exactly like scripts/build_snapshot.py output from a local HTTP server. */
class OfflineDataTest {
    private lateinit var server: ServerSocket
    private val files = HashMap<String, ByteArray>()
    private val requests = mutableListOf<String>()
    private lateinit var dir: File
    private lateinit var base: String

    private fun gz(s: String) = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(s.toByteArray()) } }.toByteArray()

    private fun publish(generated: String, extraPlace: String = "", search: String? = null, searchGenerated: String = generated) {
        val searchFields = if (search == null) "" else
            ""","searchUrl":"regions/dk.search.json.gz","searchBytes":1,"searchGenerated":"$searchGenerated""""
        files["/v1/index.json"] = """{"version":1,"generated":"$generated","regions":[{"id":"dk","name":"Denmark",
            "url":"regions/dk.json.gz","bytes":1,"placeCount":2,"generated":"$generated"$searchFields}]}""".toByteArray()
        if (search != null) {
            files["/v1/regions/dk.search.json.gz"] = gz("""{"region":"dk","generated":"$searchGenerated","search":[$search]}""")
        }
        files["/v1/regions/dk.json.gz"] = gz(
            """{"region":"dk","generated":"$generated","places":[
              {"id":"node/1","lat":55.68,"lon":12.57,"tags":{"name":"Kaf","amenity":"cafe","changing_table":"yes"}},
              {"id":"node/2","lat":55.681,"lon":12.571,"tags":{"name":"Pizza","amenity":"restaurant"}}$extraPlace
            ]}""",
        )
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("offline").toFile()
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) Unit // skip headers
                    synchronized(requests) { requests += path }
                    val body = files[path]
                    val out = sock.getOutputStream()
                    if (body == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    else {
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                    }
                    out.flush()
                }
            }
        }
        base = "http://127.0.0.1:${server.localPort}/v1"
    }

    @After fun tearDown() { server.close(); dir.deleteRecursively() }

    private val copenhagen = BoundingBox(55.67, 12.55, 55.69, 12.60)

    @Test fun downloadsQueriesAndSurvivesRestart() = runBlocking {
        publish("2026-11-15T03:17:00+00:00")
        val data = OfflineData(dir, Http("test"), base)
        assertFalse(data.hasData)
        assertNull(data.query(copenhagen, Category.CAFES))

        assertTrue(data.sync())
        assertTrue(data.hasData)
        assertEquals(listOf("Kaf"), data.query(copenhagen, Category.CAFES)!!.map { it.name })
        assertEquals(2, data.query(copenhagen, Category.CAFES_AND_FOOD)!!.size)
        assertEquals(Status.YES, data.query(copenhagen, Category.CAFES)!![0].status(Feature.CHANGING_TABLE))
        // Outside downloaded data (Malmö) -> null so the app falls back to Overpass.
        assertNull(data.query(BoundingBox(55.59, 12.98, 55.61, 13.02), Category.CAFES))

        // A fresh instance (app restart) works offline from disk.
        server.close()
        val again = OfflineData(dir, Http("test"), base)
        assertTrue(again.hasData)
        assertNotNull(again.query(copenhagen, Category.CAFES))
        assertFalse(again.sync()) // offline: sync fails but data stays
        assertEquals(1, again.query(copenhagen, Category.CAFES)!!.size)
    }

    @Test fun onlyRedownloadsWhenPublishedVersionChanges() = runBlocking {
        publish("2026-11-15T03:17:00+00:00")
        val data = OfflineData(dir, Http("test"), base)
        assertTrue(data.sync())
        requests.clear()
        assertTrue(data.sync())
        assertEquals(listOf("/v1/index.json"), requests) // unchanged: index only

        publish("2026-11-16T03:17:00+00:00",
            """,{"id":"node/3","lat":55.682,"lon":12.572,"tags":{"name":"New","amenity":"cafe"}}""")
        assertTrue(data.sync())
        assertEquals(setOf("Kaf", "New"), data.query(copenhagen, Category.CAFES)!!.map { it.name }.toSet())
    }

    @Test fun brokenDownloadKeepsOldData() = runBlocking {
        publish("2026-11-15T03:17:00+00:00")
        val data = OfflineData(dir, Http("test"), base)
        assertTrue(data.sync())
        files["/v1/index.json"] = String(files["/v1/index.json"]!!).replace("2026-11-15", "2026-11-17").toByteArray()
        files["/v1/regions/dk.json.gz"] = "not gzip".toByteArray()
        assertFalse(data.sync())
        assertEquals(1, data.query(copenhagen, Category.CAFES)!!.size)
    }

    @Test fun remembersLastCheckAndOsmEditTime() = runBlocking {
        publish("2026-11-21T11:22:05+00:00")
        files["/v1/index.json"] = String(files["/v1/index.json"]!!)
            .replace("\"generated\":\"2026-11-21T11:22:05+00:00\"}]", "\"generated\":\"2026-11-21T11:22:05+00:00\",\"osmDataUntil\":\"2026-11-20T21:21:02+00:00\"}]")
            .toByteArray()
        val data = OfflineData(dir, Http("test"), base)
        assertEquals(0L, data.lastCheckedMillis())
        val before = System.currentTimeMillis() - 2000
        assertTrue(data.sync())
        assertTrue(data.lastCheckedMillis() >= before)
        assertEquals("2026-11-20T21:21:02+00:00", data.status.value.regions.single().osmDataUntil)
        // Survives a restart (stored with the downloaded data).
        assertEquals("2026-11-20T21:21:02+00:00", OfflineData(dir, Http("test"), base).status.value.regions.single().osmDataUntil)
    }

    @Test fun ageFromGeneratedTimestamp() = runBlocking {
        publish("2026-11-15T03:17:00+00:00")
        val data = OfflineData(dir, Http("test"), base)
        assertNull(data.ageMillis())
        data.sync()
        val now = OfflineData.parseInstant("2026-11-16T03:17:00+00:00")
        assertEquals(24 * 3600 * 1000L, data.ageMillis(now))
    }

    @Test fun searchesAreasOffline() = runBlocking {
        publish(
            "2026-11-19T03:17:00+00:00",
            search = """
              {"name":"Nørrebro","kind":"suburb","lat":55.694,"lon":12.549,"in":"København","bbox":[55.68,12.53,55.71,12.57]},
              {"name":"Nørrebrogade","kind":"street","lat":55.69,"lon":12.553,"in":"Nørrebro","bbox":[55.686,12.548,55.697,12.561]},
              {"name":"Jægersborggade","kind":"street","lat":55.6915,"lon":12.5455,"in":"Nørrebro"},
              {"name":"Nørrebrogade","kind":"street","lat":56.1,"lon":10.2,"in":"Aarhus"},
              {"name":"Store Kongensgade","kind":"street","lat":55.684,"lon":12.588,"in":"Indre By"}
            """,
        )
        val data = OfflineData(dir, Http("test"), base)
        assertTrue(data.sync())
        val hereLat = 55.68; val hereLon = 12.57

        // The area itself ranks above streets that start with the same word; nearer Nørrebrogade first.
        assertEquals(
            listOf("Nørrebro \u2014 area, København", "Nørrebrogade \u2014 street, Nørrebro", "Nørrebrogade \u2014 street, Aarhus"),
            data.searchAreas("norrebro", hereLat, hereLon).map { it.displayName },
        )
        // Danish letters optional, house number and postcode ignored.
        assertEquals("Jægersborggade \u2014 street, Nørrebro",
            data.searchAreas("Jaegersborggade 10, 2200 København N", hereLat, hereLon).single().displayName)
        // Start of any word: "kongens" finds Store Kongensgade; mid-word fragments don't match.
        assertEquals(1, data.searchAreas("kongens", hereLat, hereLon).size)
        assertTrue(data.searchAreas("gade", hereLat, hereLon).isEmpty())
        // Bounding box order matches BoundingBox(south, west, north, east); streets zoom closer than areas.
        val area = data.searchAreas("Nørrebro", hereLat, hereLon).first()
        assertEquals(BoundingBox(55.68, 12.53, 55.71, 12.57), area.bbox)
        assertTrue(data.searchAreas("Jægersborggade", hereLat, hereLon).single().zoom > area.zoom)
    }

    @Test fun searchFileOnlyRedownloadsWhenItChanges() = runBlocking {
        val street = """{"name":"Jægersborggade","kind":"street","lat":55.6915,"lon":12.5455}"""
        publish("2026-11-19T03:17:00+00:00", search = street, searchGenerated = "2026-11-17T03:17:00+00:00")
        val data = OfflineData(dir, Http("test"), base)
        assertTrue(data.sync())
        assertEquals(1, data.searchAreas("Jægersborggade", 55.68, 12.57).size)

        // Next day: new café data, same weekly search file -> only the café file is fetched.
        publish("2026-11-20T03:17:00+00:00", search = street, searchGenerated = "2026-11-17T03:17:00+00:00")
        requests.clear()
        assertTrue(data.sync())
        assertEquals(listOf("/v1/index.json", "/v1/regions/dk.json.gz"), requests)
        assertEquals(1, data.searchAreas("Jægersborggade", 55.68, 12.57).size)

        // A broken new search file keeps the old one, and the café data still updates.
        publish("2026-11-24T03:17:00+00:00", search = street, searchGenerated = "2026-11-24T03:17:00+00:00")
        files["/v1/regions/dk.search.json.gz"] = "broken".toByteArray()
        assertFalse(data.sync())
        assertEquals(1, data.searchAreas("Jægersborggade", 55.68, 12.57).size)
        val restarted = OfflineData(dir, Http("test"), base)
        assertEquals(1, restarted.searchAreas("Jægersborggade", 55.68, 12.57).size)
        assertEquals("2026-11-24T03:17:00+00:00", restarted.status.value.regions.single().generated)
        // ...and is retried next time.
        publish("2026-11-24T03:17:00+00:00", search = street, searchGenerated = "2026-11-24T03:17:00+00:00")
        requests.clear()
        assertTrue(restarted.sync())
        assertEquals(listOf("/v1/index.json", "/v1/regions/dk.search.json.gz"), requests)
    }

    @Test fun oldFilesWithoutSearchStillWork() = runBlocking {
        publish("2026-11-15T03:17:00+00:00")
        val data = OfflineData(dir, Http("test"), base)
        assertTrue(data.sync())
        assertTrue(data.searchAreas("Nørrebro", 55.68, 12.57).isEmpty())
        assertEquals(1, data.query(copenhagen, Category.CAFES)!!.size)
    }
}
