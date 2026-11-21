package app.babylatte.data

import kotlinx.serialization.Serializable

/** What we know about a single amenity at a place. */
enum class Status { YES, LIMITED, NO, UNKNOWN }

/**
 * Family-relevant features, each mapped to the OpenStreetMap tags that describe it.
 * The order here is the order filters and details are shown in the UI.
 */
enum class Feature(val label: String, val babySpecific: Boolean) {
    CHANGING_TABLE("Changing table", true),
    PRAM_ACCESS("Pram / step-free access", false),
    HIGHCHAIR("Highchairs", true),
    KIDS_AREA("Play corner", true),
    BABY_FEEDING("Breastfeeding friendly", true),
    ACCESSIBLE_TOILET("Accessible toilet", false),
    TOILETS("Toilets", false),
    OUTDOOR_SEATING("Outdoor seating", false),
}

@Serializable
data class Place(
    /** OSM element type + id, e.g. "node/123". Stable across sessions. */
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: String,
    val tags: Map<String, String> = emptyMap(),
) {
    val features: Map<Feature, Status> by lazy { OsmTags.features(tags) }

    fun status(f: Feature): Status = features[f] ?: Status.UNKNOWN

    /** Number of confirmed baby-specific features. */
    val babyScore: Int get() = Feature.entries.count { it.babySpecific && status(it) == Status.YES }

    /** Baby friendly = at least one confirmed baby-specific amenity, or an explicit stroller=yes. */
    val isBabyFriendly: Boolean
        get() = babyScore > 0 || tags["stroller"] == "yes"

    val address: String?
        get() {
            val street = listOfNotNull(tags["addr:street"], tags["addr:housenumber"]).joinToString(" ")
            val city = listOfNotNull(tags["addr:postcode"], tags["addr:city"]).joinToString(" ")
            return listOf(street, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        }

    val openingHours: String? get() = tags["opening_hours"]
    val website: String? get() = tags["website"] ?: tags["contact:website"]
    val phone: String? get() = tags["phone"] ?: tags["contact:phone"]
    val osmUrl: String get() = "https://www.openstreetmap.org/$id"

    /** Added by this user and waiting for OpenStreetMap volunteers; stored only on this phone. */
    val isPending: Boolean get() = id.startsWith(PENDING_PREFIX)
    val pendingNoteUrl: String? get() = tags[TAG_NOTE_URL]
    /** For a pending place: ids of places that were already nearby when it was added. */
    val existingNearby: Set<String> get() = tags[TAG_EXISTING]?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    companion object {
        const val PENDING_PREFIX = "local/"
        const val TAG_NOTE_URL = "babylatte:note"
        const val TAG_EXISTING = "babylatte:existing"
    }
}
