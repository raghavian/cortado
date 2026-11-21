package app.babylatte.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "babylatte")

/** Everything the user saves lives only on the device. */
class UserStore(private val context: Context) {
    private val favoritesKey = stringPreferencesKey("favorites_json")
    private val pendingKey = stringPreferencesKey("pending_json")
    private val reportsKey = stringPreferencesKey("reports_json")
    private val filtersKey = stringSetPreferencesKey("filters")
    private val categoryKey = stringPreferencesKey("category")
    private val cameraKey = stringPreferencesKey("camera")
    private val json = Json { ignoreUnknownKeys = true }

    val favorites: Flow<Map<String, Place>> = context.dataStore.data.map { p -> decodeFavorites(p) }

    /** Places this user added that aren't on OpenStreetMap yet. */
    val pending: Flow<List<Place>> = context.dataStore.data.map { p -> decodeMap(p, pendingKey).values.toList() }

    suspend fun addPending(place: Place) {
        context.dataStore.edit { p -> p[pendingKey] = json.encodeToString(decodeMap(p, pendingKey) + (place.id to place)) }
    }

    suspend fun removePending(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { p -> p[pendingKey] = json.encodeToString(decodeMap(p, pendingKey) - ids.toSet()) }
    }

    val filters: Flow<Set<String>> = context.dataStore.data.map { it[filtersKey] ?: emptySet() }

    val category: Flow<Category> = context.dataStore.data.map { p ->
        p[categoryKey]?.let { runCatching { Category.valueOf(it) }.getOrNull() } ?: Category.CAFES
    }

    /** Last map position as (lat, lon, zoom). */
    val camera: Flow<Triple<Double, Double, Double>?> = context.dataStore.data.map { p ->
        p[cameraKey]?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }
            ?.let { Triple(it[0], it[1], it[2]) }
    }

    /** Reports this user sent, newest first. */
    val reports: Flow<List<SubmittedReport>> = context.dataStore.data.map { p -> decodeReports(p) }

    suspend fun addReport(report: SubmittedReport) = editReports { listOf(report) + it }

    suspend fun updateReport(report: SubmittedReport) =
        editReports { list -> list.map { if (it.noteId == report.noteId) report else it } }

    /** New places that showed up in the real data: their reports are now on the map. */
    suspend fun markPinRemoved(pendingPlaceId: String) =
        editReports { list -> list.map { if (it.placeId == pendingPlaceId) it.copy(pinRemoved = true) else it } }

    suspend fun markOnMap(pendingPlaceIds: Collection<String>) {
        if (pendingPlaceIds.isEmpty()) return
        editReports { list -> list.map { if (it.placeId in pendingPlaceIds) it.copy(onMap = true) else it } }
    }

    private suspend fun editReports(change: (List<SubmittedReport>) -> List<SubmittedReport>) {
        context.dataStore.edit { p -> p[reportsKey] = json.encodeToString(change(decodeReports(p))) }
    }

    private fun decodeReports(p: Preferences): List<SubmittedReport> =
        p[reportsKey]?.let { runCatching { json.decodeFromString<List<SubmittedReport>>(it) }.getOrNull() }
            ?: emptyList()

    suspend fun toggleFavorite(place: Place) {
        context.dataStore.edit { p ->
            val current = decodeFavorites(p).toMutableMap()
            if (current.remove(place.id) == null) current[place.id] = place
            p[favoritesKey] = json.encodeToString(current)
        }
    }

    suspend fun setFilters(filters: Set<String>) {
        context.dataStore.edit { it[filtersKey] = filters }
    }

    suspend fun setCategory(category: Category) {
        context.dataStore.edit { it[categoryKey] = category.name }
    }

    suspend fun setCamera(lat: Double, lon: Double, zoom: Double) {
        context.dataStore.edit { it[cameraKey] = "$lat,$lon,$zoom" }
    }

    private fun decodeFavorites(p: Preferences): Map<String, Place> = decodeMap(p, favoritesKey)

    private fun decodeMap(p: Preferences, key: Preferences.Key<String>): Map<String, Place> =
        p[key]?.let { runCatching { json.decodeFromString<Map<String, Place>>(it) }.getOrNull() }
            ?: emptyMap()
}
