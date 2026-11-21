package app.babylatte.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.babylatte.BuildConfig
import app.babylatte.data.Category
import app.babylatte.data.Filters
import app.babylatte.data.Place
import app.babylatte.ui.theme.BabylatteTheme
import app.babylatte.ui.theme.Fog
import app.babylatte.ui.theme.Honey
import app.babylatte.ui.theme.Sage

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BabylatteTheme { MainScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.checkForUpdates(force = false) // coming back after hours or days: pick up new data
    }
}

private const val MAX_PINS = 700

private enum class Tab(val label: String) { MAP("Map"), LIST("List"), SAVED("Saved") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val reportState by vm.reportState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    var reporting by remember { mutableStateOf<Place?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    var hasLocation by remember { mutableStateOf(granted()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = granted()
    }

    val initialCamera by produceState<Triple<Double, Double, Double>?>(null) { value = vm.initialCamera() }

    LaunchedEffect(state.error, state.info) {
        val msg = state.error ?: state.info ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.dismissMessage()
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                SearchRow(onSearch = vm::search, onAbout = { showAbout = true })
                FilterRow(state.filters, state.category, vm::toggleFilter, vm::clearFilters, vm::setCategory)
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.offline.syncing && state.offline.regions.isEmpty()) {
                        Text(
                            "Downloading café data for offline use\u2026",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    } else if (state.slow) {
                        Text(
                            "OpenStreetMap servers are busy, still loading\u2026",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                } else HorizontalDivider()
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.MAP -> Icons.Filled.Map
                                    Tab.LIST -> Icons.AutoMirrored.Filled.List
                                    Tab.SAVED -> Icons.Filled.Favorite
                                }, null,
                            )
                        },
                        label = {
                            Text(
                                when (t) {
                                    Tab.LIST -> "List (${state.visible.size})"
                                    Tab.SAVED -> "Saved (${state.favorites.size})"
                                    else -> t.label
                                },
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The map stays composed so it keeps its position while switching tabs.
            initialCamera?.let { cam ->
                MapPane(
                    // Most family-friendly first, so capping keeps the useful pins.
                    places = state.visible.take(MAX_PINS),
                    selected = state.selected,
                    initialCamera = cam,
                    commands = vm.commands,
                    hasLocationPermission = hasLocation,
                    onRequestLocationPermission = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                    onViewport = vm::onViewportChanged,
                    onUserLocation = vm::onUserLocation,
                    onPlaceClick = vm::select,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (tab == Tab.MAP && state.addStep == AddStep.Placing) {
                PlacePinOverlay(onConfirm = vm::confirmLocation, onCancel = vm::cancelAdd)
            } else if (tab == Tab.MAP) {
                MapOverlays(
                    areaChanged = state.areaChanged && !state.loading,
                    filteredOut = (state.places.size + state.pending.size - state.visible.size).coerceAtLeast(0),
                    hasPending = state.visible.any { it.isMine },
                    onSearchArea = vm::loadArea,
                    onLocate = vm::locateMe,
                    onAdd = vm::startAdd,
                )
            } else {
                if (tab == Tab.SAVED) Surface(Modifier.fillMaxSize()) {
                    LaunchedEffect(Unit) { vm.refreshReports() }
                    SavedTab(
                        favorites = Filters.sort(state.favorites.values.toList(), state.userLat, state.userLon),
                        reports = state.reports,
                        userLat = state.userLat, userLon = state.userLon,
                        onPlace = vm::select,
                        onRestore = vm::restoreReports,
                    )
                } else Surface(Modifier.fillMaxSize()) {
                    val items = if (tab == Tab.LIST) state.visible
                    else Filters.sort(state.favorites.values.toList(), state.userLat, state.userLon)
                    if (items.isEmpty()) {
                        EmptyState(
                            if (tab == Tab.SAVED) "Tap the heart on a café to save it here."
                            else if (state.filters.isNotEmpty() && state.places.isNotEmpty())
                                "No cafés here match all your filters. Try removing one."
                            else "Move the map to an area and tap “Search this area”.",
                        )
                    } else {
                        LazyColumn {
                            items(items, key = { it.id }) { p ->
                                PlaceRow(p, state.userLat, state.userLon, p.id in state.favorites) { vm.select(p) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    state.selected?.let { place ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { vm.select(null) }, sheetState = sheetState) {
            PlaceDetails(
                place = place,
                isFavorite = place.id in state.favorites,
                refLat = state.userLat,
                refLon = state.userLon,
                onToggleFavorite = { vm.toggleFavorite(place) },
                onReport = { vm.resetReport(); reporting = place },
                onShowOnMap = if (tab != Tab.MAP) ({ tab = Tab.MAP; vm.showOnMap(place) }) else null,
                onRemovePending = { vm.removePending(place) },
            )
        }
    }

    reporting?.let { place ->
        ReportDialog(
            place = place,
            state = reportState,
            onSend = { vm.sendReport(place, it) },
            onDismiss = { reporting = null; vm.resetReport() },
        )
    }

    when (val step = state.addStep) {
        is AddStep.CheckDuplicates -> AlertDialog(
            onDismissRequest = vm::cancelAdd,
            title = { Text("Already on the map?") },
            text = {
                Column {
                    Text("These places are right next to your pin. Is it one of them?")
                    Spacer(Modifier.height(8.dp))
                    step.nearby.forEach { p ->
                        TextButton(onClick = { vm.pickExisting(p) }, Modifier.fillMaxWidth()) {
                            Text(p.name, Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::notADuplicate) { Text("No, it's a new place") } },
            dismissButton = { TextButton(onClick = vm::cancelAdd) { Text("Cancel") } },
        )
        is AddStep.Form -> ReportDialog(
            place = null,
            state = reportState,
            onSend = { vm.sendNewPlace(step.lat, step.lon, it) },
            onDismiss = vm::finishAdd,
        )
        else -> Unit
    }

    if (state.placeResults.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::clearSearchResults,
            title = { Text("Cafés named \u201c${state.lastQuery}\u201d") },
            text = {
                LazyColumn {
                    items(state.placeResults, key = { it.id }) { p ->
                        PlaceRow(p, state.userLat, state.userLon, p.id in state.favorites) { vm.pickSearchResult(p) }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::clearSearchResults) { Text("Cancel") } },
            dismissButton = {
                TextButton(onClick = { vm.searchLocation(state.lastQuery) }) { Text("Search areas instead") }
            },
        )
    }

    if (state.searchResults.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::clearSearchResults,
            title = { Text("Which one?") },
            text = {
                LazyColumn {
                    items(state.searchResults) { r ->
                        TextButton(onClick = { vm.goTo(r) }, Modifier.fillMaxWidth()) {
                            Text(r.displayName, Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::clearSearchResults) { Text("Cancel") } },
            dismissButton = if (state.searchResultsOffline) {
                { TextButton(onClick = { vm.searchOnline(state.lastQuery) }) { Text("Search worldwide") } }
            } else null,
        )
    }

    if (showAbout) AboutDialog(state.offline, onCheck = { vm.checkForUpdates() }) { showAbout = false }
}

@Composable
private fun SearchRow(onSearch: (String) -> Unit, onAbout: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search a café, city or area") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") }
            },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch(query) }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAbout) { Icon(Icons.Filled.Info, "About") }
    }
}

@Composable
private fun FilterRow(
    active: Set<String>,
    category: Category,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onCategory: (Category) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = category != Category.CAFES,
                    onClick = { menu = true },
                    label = { Text(category.label) },
                    leadingIcon = { Icon(Icons.Filled.Storefront, null, Modifier.size(FilterChipDefaults.IconSize)) },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Category.entries.forEach { c ->
                        DropdownMenuItem(text = { Text(c.label) }, onClick = { menu = false; onCategory(c) })
                    }
                }
            }
        }
        if (active.isNotEmpty()) item {
            FilterChip(
                selected = false, onClick = onClear, label = { Text("Clear") },
                leadingIcon = { Icon(Icons.Filled.FilterListOff, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        items(Filters.all, key = { it.first }) { (key, label) ->
            FilterChip(
                selected = key in active,
                onClick = { onToggle(key) },
                label = { Text(label) },
                leadingIcon = { Icon(filterIcon(key), null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
    }
}

@Composable
private fun MapOverlays(
    areaChanged: Boolean,
    filteredOut: Int,
    hasPending: Boolean,
    onSearchArea: () -> Unit,
    onLocate: () -> Unit,
    onAdd: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(areaChanged, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
            Button(onClick = onSearchArea) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search this area")
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
            SmallFloatingActionButton(onClick = onLocate) { Icon(Icons.Filled.MyLocation, "My location") }
            Spacer(Modifier.height(12.dp))
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.AddLocationAlt, null) },
                text = { Text("Add café") },
            )
        }
        Surface(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 28.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                LegendItem(Sage, "Baby friendly")
                LegendItem(Honey, "Pram access")
                LegendItem(Fog, "No info yet")
                if (hasPending) LegendItem(app.babylatte.ui.theme.Espresso, "Yours, in review")
                if (filteredOut > 0) {
                    Text("$filteredOut hidden by filters", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Fixed pin in the middle of the map; the user pans the map underneath it. */
@Composable
private fun PlacePinOverlay(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Surface(
            Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Where is the café?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Move the map so the pin is on the café's entrance. Zoom in for accuracy.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // Pin tip sits exactly on the map centre.
        Icon(
            Icons.Filled.Place, null,
            Modifier.align(Alignment.Center).size(48.dp).offset(y = (-24).dp),
            tint = app.babylatte.ui.theme.Clay,
        )
        Box(Modifier.align(Alignment.Center).size(6.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape))
        Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = onConfirm, Modifier.weight(1f)) { Text("It's here") }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutDialog(offline: app.babylatte.data.OfflineStatus, onCheck: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("babylatte ${BuildConfig.VERSION_NAME}") },
        text = {
            Column {
                Text("Find pram- and baby-friendly cafés anywhere in the world.")
                Spacer(Modifier.height(12.dp))
                Text("Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Privacy: no account, no tracking, no ads. Your GPS location never leaves your phone; it is only " +
                        "used to centre the map. Café data is downloaded daily from GitHub; the map area you view is " +
                        "sent to OpenStreetMap services to load map tiles (and cafés outside the offline data). Saved cafés and filters stay on your phone. Reports you send are " +
                        "posted anonymously and publicly to OpenStreetMap.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (offline.regions.isEmpty()) "Offline data: not downloaded yet."
                    else "Offline data: ${offline.placeCount} places in " +
                        offline.regions.joinToString { it.name } + ", downloaded " +
                        shortTime(offline.regions.first().generated) + "." +
                        (offline.regions.first().osmDataUntil?.let { " Includes OpenStreetMap edits up to ${shortTime(it)}." } ?: "") +
                        " New edits usually appear within a day.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onCheck, enabled = !offline.syncing, contentPadding = PaddingValues(0.dp)) {
                    Text(if (offline.syncing) "Checking\u2026" else "Check for updates")
                }
                Spacer(Modifier.height(8.dp))
                Text("Missing info? Open a café and tap “Add or correct info”.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** "2026-11-20T21:21:02+00:00" -> "20 Nov, 22:21" in the phone's time zone. */
private fun shortTime(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
}.getOrDefault(iso.take(10))
