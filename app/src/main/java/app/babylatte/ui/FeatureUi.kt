package app.babylatte.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ChildFriendly
import androidx.compose.material.icons.filled.Deck
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Wc
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.babylatte.data.Feature
import app.babylatte.data.Filters
import app.babylatte.data.Status
import app.babylatte.ui.theme.Clay
import app.babylatte.ui.theme.Fog
import app.babylatte.ui.theme.Honey
import app.babylatte.ui.theme.Sage

fun Feature.icon(): ImageVector = when (this) {
    Feature.CHANGING_TABLE -> Icons.Filled.BabyChangingStation
    Feature.PRAM_ACCESS -> Icons.Filled.ChildFriendly
    Feature.HIGHCHAIR -> Icons.Filled.EventSeat
    Feature.KIDS_AREA -> Icons.Filled.Toys
    Feature.BABY_FEEDING -> Icons.Filled.VolunteerActivism
    Feature.ACCESSIBLE_TOILET -> Icons.Filled.Accessible
    Feature.TOILETS -> Icons.Filled.Wc
    Feature.OUTDOOR_SEATING -> Icons.Filled.Deck
}

fun filterIcon(key: String): ImageVector =
    if (key == Filters.BABY_FRIENDLY) Icons.Filled.ChildCare else Feature.valueOf(key).icon()

fun Status.color(): Color = when (this) {
    Status.YES -> Sage
    Status.LIMITED -> Honey
    Status.NO -> Clay
    Status.UNKNOWN -> Fog
}

fun Status.label(): String = when (this) {
    Status.YES -> "Yes"
    Status.LIMITED -> "Limited"
    Status.NO -> "No"
    Status.UNKNOWN -> "Unknown"
}
