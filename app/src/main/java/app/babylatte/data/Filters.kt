package app.babylatte.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Filters {
    const val BABY_FRIENDLY = "BABY_FRIENDLY"

    /** Filters the user can toggle, as (key, label). */
    val all: List<Pair<String, String>> =
        listOf(BABY_FRIENDLY to "Baby friendly") + Feature.entries.map { it.name to it.label }

    /**
     * A place matches when every active filter is satisfied. Pram / step-free access also
     * accepts "limited" (e.g. one small step), since parents can usually manage that.
     */
    fun matches(place: Place, active: Set<String>): Boolean = active.all { key ->
        if (key == BABY_FRIENDLY) return@all place.isBabyFriendly
        val feature = runCatching { Feature.valueOf(key) }.getOrNull() ?: return@all true
        val s = place.status(feature)
        s == Status.YES || (feature == Feature.PRAM_ACCESS && s == Status.LIMITED)
    }

    /** Most family-friendly first, then nearest. */
    fun sort(places: List<Place>, refLat: Double?, refLon: Double?): List<Place> =
        places.sortedWith(
            compareByDescending<Place> { it.babyScore + if (it.status(Feature.PRAM_ACCESS) == Status.YES) 1 else 0 }
                .thenBy { if (refLat != null && refLon != null) distanceMeters(refLat, refLon, it.lat, it.lon) else 0.0 }
        )

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun formatDistance(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", m / 1000)
}
