package app.babylatte.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.babylatte.data.NoteStatus
import app.babylatte.data.Place
import app.babylatte.data.SubmittedReport
import app.babylatte.ui.theme.Fog
import app.babylatte.ui.theme.Honey
import app.babylatte.ui.theme.Sage

/** Saved cafés, then the reports this user sent and what happened to them. */
@Composable
fun SavedTab(
    favorites: List<Place>,
    reports: List<SubmittedReport>,
    userLat: Double?,
    userLon: Double?,
    onPlace: (Place) -> Unit,
    onRestore: (String) -> Unit,
) {
    var restoring by remember { mutableStateOf(false) }
    if (restoring) RestoreDialog(onRestore = { onRestore(it); restoring = false }, onDismiss = { restoring = false })
    LazyColumn {
        item { SectionHeader("Saved cafés") }
        if (favorites.isEmpty()) item { Hint("Tap the heart on a café to save it here.") }
        items(favorites, key = { it.id }) { p ->
            PlaceRow(p, userLat, userLon, isFavorite = true) { onPlace(p) }
            HorizontalDivider()
        }

        item { SectionHeader("My reports") }
        if (reports.isEmpty()) {
            item { Hint("Info you add or correct shows up here, so you can follow it until it's on the map.") }
        } else {
            item {
                Hint(
                    "Reports go to OpenStreetMap volunteers. When they've updated the map, it reaches " +
                        "everyone with the next daily data update.",
                )
            }
        }
        items(reports, key = { it.noteId }) { r ->
            ReportRow(r)
            HorizontalDivider()
        }
        item {
            TextButton(onClick = { restoring = true }, Modifier.padding(horizontal = 8.dp)) {
                Text("Missing reports? Restore them from OpenStreetMap")
            }
        }
    }
}

/** Paste note links or numbers (e.g. after reinstalling the app) to bring back reports and your pins. */
@Composable
private fun RestoreDialog(onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore reports") },
        text = {
            Column {
                Text(
                    "Reports are kept on your phone, so reinstalling the app loses them. Paste the links or " +
                        "numbers of your notes (one or many), e.g. openstreetmap.org/note/5640650.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("5640650, 5640428, …") }, minLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onRestore(text) }, enabled = text.isNotBlank()) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class StatusLook(val label: String, val icon: ImageVector, val color: Color)

private fun SubmittedReport.look(): StatusLook = when {
    onMap -> StatusLook("On the map", Icons.Filled.DoneAll, Sage)
    status == NoteStatus.CLOSED -> StatusLook("Resolved by a mapper", Icons.Filled.CheckCircle, Sage)
    status == NoteStatus.HIDDEN -> StatusLook("Removed by moderators", Icons.Filled.VisibilityOff, Fog)
    else -> StatusLook("Waiting for review", Icons.Filled.HourglassTop, Honey)
}

@Composable
private fun ReportRow(r: SubmittedReport) {
    val context = LocalContext.current
    val look = r.look()
    Row(
        Modifier.fillMaxWidth()
            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.noteUrl))) } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(look.icon, null, Modifier.size(22.dp).padding(top = 2.dp), tint = look.color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.placeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = if (r.isNewPlace) "New café" else "Correction"
            val sent = DateUtils.getRelativeTimeSpanString(r.createdAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
            Text("$kind · sent $sent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(look.label, style = MaterialTheme.typography.labelLarge, color = look.color)
            r.mapperComment?.let {
                Text(
                    "“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("View note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
