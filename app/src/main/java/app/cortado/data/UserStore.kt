package app.cortado.data

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

private val Context.dataStore by preferencesDataStore(name = "cortado")

/** Everything the user saves lives only on the device. */
class UserStore(private val context: Context) {
    private val favoritesKey = stringPreferencesKey("favorites_json")
    private val filtersKey = stringSetPreferencesKey("filters")
    private val categoryKey = stringPreferencesKey("category")
    private val cameraKey = stringPreferencesKey("camera")
    private val json = Json { ignoreUnknownKeys = true }

    val favorites: Flow<Map<String, Place>> = context.dataStore.data.map { p -> decodeFavorites(p) }

    val filters: Flow<Set<String>> = context.dataStore.data.map { it[filtersKey] ?: emptySet() }

    val category: Flow<Category> = context.dataStore.data.map { p ->
        p[categoryKey]?.let { runCatching { Category.valueOf(it) }.getOrNull() } ?: Category.CAFES
    }

    /** Last map position as (lat, lon, zoom). */
    val camera: Flow<Triple<Double, Double, Double>?> = context.dataStore.data.map { p ->
        p[cameraKey]?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }
            ?.let { Triple(it[0], it[1], it[2]) }
    }

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

    private fun decodeFavorites(p: Preferences): Map<String, Place> =
        p[favoritesKey]?.let { runCatching { json.decodeFromString<Map<String, Place>>(it) }.getOrNull() }
            ?: emptyMap()
}
