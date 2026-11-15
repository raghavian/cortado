package app.babylatte.data

/** Translates OpenStreetMap tags into family-friendly [Feature]s. */
object OsmTags {

    fun features(tags: Map<String, String>): Map<Feature, Status> = Feature.entries.associateWith { f ->
        when (f) {
            Feature.CHANGING_TABLE -> firstKnown(
                yesNo(tags["changing_table"]),
                yesNo(tags["diaper"]), // legacy tag, may also hold a count
                yesNo(tags["toilets:changing_table"]),
            )
            Feature.PRAM_ACCESS -> firstKnown(
                yesNo(tags["stroller"]),
                yesNo(tags["wheelchair"]),
                if (tags["entrance:step_count"] == "0" || tags["step_count"] == "0") Status.YES else Status.UNKNOWN,
                yesNo(tags["ramp"]).let { if (it == Status.NO) Status.UNKNOWN else it },
            )
            Feature.HIGHCHAIR -> yesNo(tags["highchair"])
            Feature.KIDS_AREA -> firstKnown(
                yesNo(tags["kids_area"]),
                yesNo(tags["kids_area:indoor"]),
                yesNo(tags["playground"]),
            )
            Feature.BABY_FEEDING -> firstKnown(
                yesNo(tags["baby_feeding"]),
                yesNo(tags["breastfeeding"]),
            )
            Feature.ACCESSIBLE_TOILET -> yesNo(tags["toilets:wheelchair"])
            Feature.TOILETS -> firstKnown(
                yesNo(tags["toilets"]),
                if (tags["toilets:wheelchair"] == "yes" || tags["changing_table"] == "yes") Status.YES else Status.UNKNOWN,
            )
            Feature.OUTDOOR_SEATING -> yesNo(tags["outdoor_seating"])
        }
    }

    /** Primary OSM tag a user report for this feature corresponds to (used in OSM notes). */
    fun reportTag(f: Feature): String = when (f) {
        Feature.CHANGING_TABLE -> "changing_table"
        Feature.PRAM_ACCESS -> "stroller"
        Feature.HIGHCHAIR -> "highchair"
        Feature.KIDS_AREA -> "kids_area"
        Feature.BABY_FEEDING -> "baby_feeding"
        Feature.ACCESSIBLE_TOILET -> "toilets:wheelchair"
        Feature.TOILETS -> "toilets"
        Feature.OUTDOOR_SEATING -> "outdoor_seating"
    }

    internal fun yesNo(value: String?): Status {
        val v = value?.trim()?.lowercase() ?: return Status.UNKNOWN
        return when {
            v == "yes" || v == "designated" || v == "permissive" || v == "customers" -> Status.YES
            v == "limited" || v == "partial" -> Status.LIMITED
            v == "no" || v == "0" -> Status.NO
            v.toIntOrNull()?.let { it > 0 } == true -> Status.YES // counts, e.g. highchair=3
            else -> Status.UNKNOWN
        }
    }

    private fun firstKnown(vararg s: Status): Status = s.firstOrNull { it != Status.UNKNOWN } ?: Status.UNKNOWN
}
