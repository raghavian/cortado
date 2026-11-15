package app.cortado.ui

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
import app.cortado.BuildConfig
import app.cortado.data.Category
import app.cortado.data.Filters
import app.cortado.data.Place
import app.cortado.ui.theme.CortadoTheme
import app.cortado.ui.theme.Fog
import app.cortado.ui.theme.Honey
import app.cortado.ui.theme.Sage

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CortadoTheme { MainScreen(vm) } }
    }
}

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
                    if (state.slow) {
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
                    places = state.visible,
                    selectedId = state.selected?.id,
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
            if (tab == Tab.MAP) {
                MapOverlays(
                    areaChanged = state.areaChanged && !state.loading,
                    filteredOut = state.places.size - state.visible.size,
                    onSearchArea = vm::loadArea,
                    onLocate = vm::locateMe,
                )
            } else {
                Surface(Modifier.fillMaxSize()) {
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
        )
    }

    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun SearchRow(onSearch: (String) -> Unit, onAbout: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search a city or neighbourhood") },
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
private fun MapOverlays(areaChanged: Boolean, filteredOut: Int, onSearchArea: () -> Unit, onLocate: () -> Unit) {
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
                if (filteredOut > 0) {
                    Text("$filteredOut hidden by filters", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cortado ${BuildConfig.VERSION_NAME}") },
        text = {
            Column {
                Text("Find pram- and baby-friendly cafés anywhere in the world.")
                Spacer(Modifier.height(12.dp))
                Text("Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Privacy: no account, no tracking, no ads. Your GPS location never leaves your phone; it is only " +
                        "used to centre the map. The map area you view is sent to OpenStreetMap services to load " +
                        "map tiles and cafés. Saved cafés and filters stay on your phone. Reports you send are " +
                        "posted anonymously and publicly to OpenStreetMap.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("Missing info? Open a café and tap “Add or correct info”.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
