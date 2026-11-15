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

    private fun publish(generated: String, extraPlace: String = "") {
        files["/v1/index.json"] = """{"version":1,"generated":"$generated","regions":[{"id":"dk","name":"Denmark",
            "url":"regions/dk.json.gz","bytes":1,"placeCount":2,"generated":"$generated"}]}""".toByteArray()
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

    @Test fun ageFromGeneratedTimestamp() = runBlocking {
        publish("2026-11-15T03:17:00+00:00")
        val data = OfflineData(dir, Http("test"), base)
        assertNull(data.ageMillis())
        data.sync()
        val now = OfflineData.parseInstant("2026-11-16T03:17:00+00:00")
        assertEquals(24 * 3600 * 1000L, data.ageMillis(now))
    }
}
