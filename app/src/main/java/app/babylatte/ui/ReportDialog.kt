package app.babylatte.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.babylatte.data.Feature
import app.babylatte.data.Place
import app.babylatte.data.Report
import app.babylatte.data.Status

private val NEW_PLACE_TYPES = listOf("cafe" to "Café", "restaurant" to "Restaurant", "ice_cream" to "Ice cream")

/**
 * Full-screen form for reporting family facilities, or (when [place] is null) for adding a café
 * that's missing from the map. Either way it's sent as an anonymous OpenStreetMap note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDialog(
    place: Place?,
    state: ReportState,
    onSend: (Report) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = place == null
    val answers = remember { mutableStateMapOf<Feature, Status>() }
    var comment by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var amenity by remember { mutableStateOf("cafe") }
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (place == null) "Add a café" else "Report for ${place.name}", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") } },
                )
            },
        ) { padding ->
            when (state) {
                is ReportState.Sent -> Column(
                    Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(16.dp))
                    Text("Thank you!", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (isNew) "Your suggestion was posted as an OpenStreetMap note. Until volunteer mappers " +
                            "add it to the map (usually days to weeks), it shows as a pending pin on your phone."
                        else "Your report was posted as an OpenStreetMap note. Volunteer mappers usually review " +
                            "notes within days to weeks; once they update the map, the info will appear here.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.noteUrl))) }
                    }) { Text("View your note") }
                    Button(onClick = onDismiss) { Text("Done") }
                }

                else -> Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    if (isNew) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(120) },
                            label = { Text("Name *") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Type", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            NEW_PLACE_TYPES.forEachIndexed { i, (value, label) ->
                                SegmentedButton(
                                    selected = amenity == value,
                                    onClick = { amenity = value },
                                    shape = SegmentedButtonDefaults.itemShape(i, NEW_PLACE_TYPES.size),
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Family facilities (optional)", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Only answer what you saw yourself. Leave the rest on “Don't know”.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Feature.entries.forEach { f ->
                        val options = buildList {
                            add(Status.YES)
                            if (f == Feature.PRAM_ACCESS) add(Status.LIMITED)
                            add(Status.NO)
                            add(Status.UNKNOWN)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(f.icon(), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(f.label, style = MaterialTheme.typography.titleSmall)
                        }
                        if (f == Feature.PRAM_ACCESS) {
                            Text("Can you get in with a pram without help? “Limited” = one small step.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val current = answers[f] ?: Status.UNKNOWN
                            options.forEachIndexed { i, s ->
                                SegmentedButton(
                                    selected = current == s,
                                    onClick = { answers[f] = s },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text(if (s == Status.UNKNOWN) "Don't know" else s.label(), maxLines = 1) }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it.take(1000) },
                        label = { Text("Anything else? (optional)") },
                        placeholder = {
                            Text(if (isNew) "e.g. entrance is around the corner" else "e.g. changing table is in the downstairs toilet")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your report is published anonymously and publicly on OpenStreetMap under the ODbL licence. " +
                            "Please don't include personal information.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state is ReportState.Failed) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    val report = Report(
                        answers.toMap(), comment,
                        newPlace = if (isNew) app.babylatte.data.NewPlace(name.trim(), amenity) else null,
                    )
                    Button(
                        onClick = { onSend(report) },
                        enabled = (if (isNew) name.isNotBlank() else !report.isEmpty) && state !is ReportState.Sending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state is ReportState.Sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (isNew) "Add café" else "Send report")
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
