package app.babylatte.data

import java.text.Normalizer

object Pending {
    private const val MATCH_RADIUS_M = 80.0

    /** Radius for remembering which places already existed when the user added theirs. */
    const val EXISTING_RADIUS_M = 150.0

    /**
     * Ids of the user's pending places that now exist in the real data: a *new* place (not one
     * that was already there when the user added theirs) with a matching name within ~80 m.
     * Those can be dropped, since the official version has arrived.
     */
    fun resolved(pending: List<Place>, places: List<Place>): Set<String> = pending.filter { p ->
        val name = normalize(p.name)
        val existing = p.existingNearby
        places.any { real ->
            !real.isPending && real.id !in existing &&
                Filters.distanceMeters(p.lat, p.lon, real.lat, real.lon) <= MATCH_RADIUS_M &&
                namesMatch(name, normalize(real.name))
        }
    }.mapTo(HashSet()) { it.id }

    /**
     * Same name, allowing for a prefix like "Café" ("Lille Kop" vs "Café Lille Kop"), but not a
     * short generic name inside a longer one ("Cafe" vs "Cafe Lune"), which used to remove pins wrongly.
     */
    fun namesMatch(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 5 && long.contains(short) && short.length * 10 >= long.length * 6
    }

    /** Ids of the places within [EXISTING_RADIUS_M] of a spot, stored with a new pending place. */
    fun existingIds(places: List<Place>, lat: Double, lon: Double): String =
        places.filter { !it.isPending && Filters.distanceMeters(lat, lon, it.lat, it.lon) <= EXISTING_RADIUS_M }
            .joinToString(",") { it.id }

    /** Your own places are always shown first (never hidden by filters or the pin limit). */
    fun withPending(visibleReal: List<Place>, pending: List<Place>): List<Place> {
        val (mine, others) = visibleReal.partition { it.isMine }
        return pending + mine + others
    }

    /** How long a closed report keeps showing while the app's data catches up with the mapper's edit. */
    private const val CLOSED_GRACE_MS = 3L * 24 * 60 * 60 * 1000

    /**
     * Shows your own reports about existing cafés right away: their reported facilities are laid
     * over the OSM tags, and the place is marked as yours (dark pin), until the data has them
     * ([SubmittedReport.onMap]), the note is hidden, or a mapper closed it more than 3 days ago.
     */
    fun applyMyReports(places: List<Place>, reports: List<SubmittedReport>, now: Long = System.currentTimeMillis()): List<Place> {
        val active = reports
            .filter { !it.isNewPlace && !it.onMap && it.reportedTags.isNotEmpty() && isActive(it, now) }
            .sortedBy { it.createdAt } // a later report overrides an earlier one
            .groupBy { it.placeId }
        if (active.isEmpty()) return places
        return places.map { p ->
            val mine = active[p.id] ?: return@map p
            p.copy(tags = mine.fold(p.tags) { tags, r -> tags + r.reportedTags } + (Place.TAG_MY_REPORT to mine.last().noteUrl))
        }
    }

    private fun isActive(r: SubmittedReport, now: Long): Boolean = when (r.status) {
        NoteStatus.OPEN -> true
        NoteStatus.HIDDEN -> false
        NoteStatus.CLOSED -> r.closedAt?.let { ReportTracker.utc(it) }?.let { now - it < CLOSED_GRACE_MS } ?: false
    }

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
