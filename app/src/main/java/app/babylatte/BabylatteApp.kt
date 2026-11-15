package app.babylatte

import android.app.Application
import app.babylatte.data.GeocodingClient
import app.babylatte.data.Http
import app.babylatte.data.NotesClient
import app.babylatte.data.OverpassClient
import app.babylatte.data.PlaceCache
import app.babylatte.data.OfflineData
import app.babylatte.data.SyncWorker
import app.babylatte.data.UserStore
import org.osmdroid.config.Configuration

class BabylatteApp : Application() {
    lateinit var overpass: OverpassClient
    lateinit var geocoding: GeocodingClient
    lateinit var notes: NotesClient
    lateinit var store: UserStore
    lateinit var cache: PlaceCache
    lateinit var offline: OfflineData

    override fun onCreate() {
        super.onCreate()
        val userAgent = "babylatte/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.APPLICATION_ID})"
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
