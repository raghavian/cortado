package app.cortado

import android.app.Application
import app.cortado.data.GeocodingClient
import app.cortado.data.Http
import app.cortado.data.NotesClient
import app.cortado.data.OverpassClient
import app.cortado.data.PlaceCache
import app.cortado.data.OfflineData
import app.cortado.data.SyncWorker
import app.cortado.data.UserStore
import org.osmdroid.config.Configuration

class CortadoApp : Application() {
    lateinit var overpass: OverpassClient
    lateinit var geocoding: GeocodingClient
    lateinit var notes: NotesClient
    lateinit var store: UserStore
    lateinit var cache: PlaceCache
    lateinit var offline: OfflineData

    override fun onCreate() {
        super.onCreate()
        val userAgent = "Cortado/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"
        // OSM tile servers require a unique, identifying user agent.
        Configuration.getInstance().apply {
            this.userAgentValue = userAgent
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
        val http = Http(userAgent)
        overpass = OverpassClient(http)
        geocoding = GeocodingClient(http)
        notes = NotesClient(http, BuildConfig.OSM_API)
        store = UserStore(this)
        offline = OfflineData(filesDir.resolve("offline"), http, BuildConfig.SNAPSHOT_URL)
        cache = PlaceCache(filesDir.resolve("places-cache.json"))
        SyncWorker.schedule(this)
    }
}
