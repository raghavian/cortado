package app.babylatte.data

import java.text.Normalizer

object Pending {
    private const val MATCH_RADIUS_M = 80.0

    /**
     * Ids of the user's pending places that now exist in the real data: a place with a matching
     * name within ~80 m. Those can be dropped, since the official version has arrived.
     */
    fun resolved(pending: List<Place>, places: List<Place>): Set<String> = pending.filter { p ->
        val name = normalize(p.name)
        places.any { real ->
            !real.isPending &&
                Filters.distanceMeters(p.lat, p.lon, real.lat, real.lon) <= MATCH_RADIUS_M &&
                normalize(real.name).let { it == name || it.contains(name) || name.contains(it) }
        }
    }.mapTo(HashSet()) { it.id }

    /** Existing places close to [lat]/[lon], used to ask "is it one of these?" before adding. */
    fun nearby(places: List<Place>, lat: Double, lon: Double, radiusM: Double = 40.0): List<Place> =
        places.map { it to Filters.distanceMeters(lat, lon, it.lat, it.lon) }
            .filter { it.second <= radiusM }
            .sortedBy { it.second }
            .map { it.first }
            .take(5)

    /** Lowercase, strip accents and punctuation, fold Nordic letters (ø→o, æ→ae, å→a, ß→ss). */
    fun normalize(s: String): String =
        Normalizer.normalize(
            s.lowercase().replace("ø", "o").replace("æ", "ae").replace("å", "a").replace("ß", "ss"),
            Normalizer.Form.NFD,
        )
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9]"), "")
}
