package app.babylatte.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.babylatte.data.Feature
import app.babylatte.data.Filters
import app.babylatte.data.Place
import app.babylatte.data.Status

private fun categoryLabel(c: String) = when (c) {
    "cafe" -> "Café"
    "restaurant" -> "Restaurant"
    "ice_cream" -> "Ice cream"
    "fast_food" -> "Fast food"
    else -> c.replaceFirstChar { it.uppercase() }
}

/** Compact row used in the list and saved tabs. */
@Composable
fun PlaceRow(place: Place, refLat: Double?, refLon: Double?, isFavorite: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    place.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (isFavorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Favorite, "Saved", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            val sub = buildList {
                add(categoryLabel(place.category))
                if (refLat != null && refLon != null)
                    add(Filters.formatDistance(Filters.distanceMeters(refLat, refLon, place.lat, place.lon)))
                place.address?.let { add(it) }
            }.joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val known = Feature.entries.filter { place.status(it) == Status.YES || place.status(it) == Status.LIMITED }
            if (known.isEmpty()) {
                Text("No family info yet — tap to add", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    known.forEach { f ->
                        Icon(f.icon(), f.label, Modifier.size(20.dp), tint = place.status(f).color())
                    }
                }
            }
        }
    }
}

/** Full details shown in the bottom sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDetails(
    place: Place,
    isFavorite: Boolean,
    refLat: Double?,
    refLon: Double?,
    onToggleFavorite: () -> Unit,
    onReport: () -> Unit,
    onShowOnMap: (() -> Unit)?,
    onRemovePending: () -> Unit = {},
) {
    val context = LocalContext.current
    fun open(uri: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val sub = buildList {
                    add(categoryLabel(place.category))
                    if (refLat != null && refLon != null)
                        add(Filters.formatDistance(Filters.distanceMeters(refLat, refLon, place.lat, place.lon)) + " away")
                }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                place.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (isFavorite) "Remove from saved" else "Save",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        place.openingHours?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(it.replace("; ", "\n"), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { open("geo:0,0?q=${place.lat},${place.lon}(${Uri.encode(place.name)})") },
                label = { Text("Directions") },
                leadingIcon = { Icon(Icons.Filled.Directions, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
            place.website?.let { site ->
                AssistChip(
                    onClick = { open(if (site.startsWith("http")) site else "https://$site") },
                    label = { Text("Website") },
                    leadingIcon = { Icon(Icons.Filled.Language, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
            }
            place.phone?.let { phone ->
                AssistChip(
                    onClick = { open("tel:${phone.filter { it.isDigit() || it == '+' }}") },
                    label = { Text("Call") },
                    leadingIcon = { Icon(Icons.Filled.Call, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
            }
            onShowOnMap?.let {
                AssistChip(
                    onClick = it,
                    label = { Text("Show on map") },
                    leadingIcon = { Icon(Icons.Filled.Map, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Family facilities", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Feature.entries.forEach { f -> FeatureLine(f, place.status(f)) }

        Spacer(Modifier.height(16.dp))
        if (place.isPending) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Text("Added by you, waiting for review", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Only you can see this pin for now. Once OpenStreetMap volunteers add the place, " +
                            "it appears for everyone and this pending copy disappears automatically.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        place.pendingNoteUrl?.let { url -> OutlinedButton(onClick = { open(url) }) { Text("View note") } }
                        OutlinedButton(onClick = onRemovePending) { Text("Remove") }
                    }
                }
            }
            return@Column
        }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) {
                Text("Been here with a little one?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Share what you found. Your report goes to OpenStreetMap so it helps every parent.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onReport) {
                    Icon(Icons.Filled.RateReview, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add or correct info")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { open(place.osmUrl) }) { Text("View on OpenStreetMap") }
    }
}

@Composable
private fun FeatureLine(feature: Feature, status: Status) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(feature.icon(), null, Modifier.size(22.dp), tint = status.color())
        Spacer(Modifier.width(12.dp))
        Text(feature.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        val icon = when (status) {
            Status.YES -> Icons.Filled.Check
            Status.LIMITED -> Icons.Filled.Remove
            Status.NO -> Icons.Filled.Close
            Status.UNKNOWN -> Icons.Filled.QuestionMark
        }
        Icon(icon, null, Modifier.size(16.dp), tint = status.color())
        Spacer(Modifier.width(4.dp))
        Text(status.label(), style = MaterialTheme.typography.labelLarge, color = status.color())
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
