package app.babylatte.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.babylatte.data.BoundingBox
import app.babylatte.data.Feature
import app.babylatte.data.Place
import app.babylatte.data.Status
import app.babylatte.ui.theme.Espresso
import app.babylatte.ui.theme.Fog
import app.babylatte.ui.theme.Honey
import app.babylatte.ui.theme.Sage
import kotlinx.coroutines.flow.Flow
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private class MapHolder(context: Context) {
    val map = MapView(context)
    val markers = FolderOverlay()
    val location = MyLocationNewOverlay(GpsMyLocationProvider(context), map)
    val icons = MarkerIcons(context)
}

/** The OpenStreetMap view with one pin per place. Pins are coloured by family-friendliness. */
@Composable
fun MapPane(
    places: List<Place>,
    selected: Place?,
    initialCamera: Triple<Double, Double, Double>,
    commands: Flow<MapCommand>,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onViewport: (BoundingBox, Double, Double, Double) -> Unit,
    onUserLocation: (Double, Double) -> Unit,
    onPlaceClick: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasPermission by rememberUpdatedState(hasLocationPermission)
    val viewportCb by rememberUpdatedState(onViewport)
    val placeClickCb by rememberUpdatedState(onPlaceClick)
    val pendingLocate = remember { booleanArrayOf(false) }
    val holder = remember {
        MapHolder(context).apply {
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setMultiTouchControls(true)
            map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            map.minZoomLevel = 4.0
            map.maxZoomLevel = 19.5
            map.isTilesScaledToDpi = true
            map.controller.setZoom(initialCamera.third)
            map.controller.setCenter(GeoPoint(initialCamera.first, initialCamera.second))
            map.overlays.add(markers)
            map.overlays.add(location)
            map.overlays.add(CopyrightOverlay(context))
            map.addMapListener(DelayedMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?) = report()
                override fun onZoom(event: ZoomEvent?) = report()
                fun report(): Boolean {
                    val b = map.boundingBox
                    viewportCb(
                        BoundingBox(b.latSouth, b.lonWest, b.latNorth, b.lonEast),
                        map.mapCenter.latitude, map.mapCenter.longitude, map.zoomLevelDouble,
                    )
                    return false
                }
            }, 600))
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.map.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.location.disableMyLocation()
        }
    }

    // First viewport report so the initial area loads even if the user never touches the map.
    LaunchedEffect(Unit) {
        holder.map.post {
            val b = holder.map.boundingBox
            onViewport(
                BoundingBox(b.latSouth, b.lonWest, b.latNorth, b.lonEast),
                holder.map.mapCenter.latitude, holder.map.mapCenter.longitude, holder.map.zoomLevelDouble,
            )
        }
    }

    fun startLocation(animate: Boolean) {
        holder.location.enableMyLocation()
        holder.location.runOnFirstFix {
            val p = holder.location.myLocation ?: return@runOnFirstFix
            holder.map.post {
                onUserLocation(p.latitude, p.longitude)
                if (animate) holder.map.controller.animateTo(p, 16.0, 800L)
            }
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) startLocation(animate = pendingLocate[0])
        pendingLocate[0] = false
    }

    LaunchedEffect(commands) {
        commands.collect { cmd ->
            when (cmd) {
                is MapCommand.MoveTo -> holder.map.controller.animateTo(GeoPoint(cmd.lat, cmd.lon), cmd.zoom, 800L)
                is MapCommand.Focus -> holder.map.apply {
                    controller.setZoom(17.5)
                    controller.setCenter(GeoPoint(cmd.lat, cmd.lon))
                    // Shift the view so the pin sits ~quarter-height above centre, clear of the sheet.
                    post { scrollBy(0, height / 4) }
                }
                is MapCommand.Fit -> holder.map.zoomToBoundingBox(
                    org.osmdroid.util.BoundingBox(cmd.bbox.north, cmd.bbox.east, cmd.bbox.south, cmd.bbox.west),
                    true, 48,
                )
                MapCommand.LocateMe -> {
                    if (!hasPermission) {
                        pendingLocate[0] = true
                        onRequestLocationPermission()
                    } else {
                        val p = holder.location.myLocation
                        if (p != null) holder.map.controller.animateTo(p, 16.0, 800L) else startLocation(animate = true)
                    }
                }
            }
        }
    }

    AndroidView(
        factory = { holder.map },
        modifier = modifier,
        update = { map ->
            holder.markers.items.clear()
            // Draw unknowns first so the family-friendly pins sit on top, and the selected place
            // last of all (added even if the filters would hide it). Other pins fade back while
            // something is selected so the chosen one stands out.
            val others = places.filter { it.id != selected?.id }.sortedBy { it.babyScore }
            (others + listOfNotNull(selected)).forEach { place ->
                val isSelected = place.id == selected?.id
                holder.markers.add(Marker(map).apply {
                    position = GeoPoint(place.lat, place.lon)
                    title = place.name
                    icon = holder.icons.forPlace(place, isSelected)
                    alpha = if (selected != null && !isSelected) 0.6f else 1f
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> placeClickCb(place); true }
                })
            }
            map.invalidate()
        },
    )
}

/** Draws simple teardrop pins: green = baby friendly, amber = pram access, grey = no info yet. */
private class MarkerIcons(private val context: Context) {
    private val cache = HashMap<Pair<Int, Boolean>, Drawable>()

    fun forPlace(place: Place, selected: Boolean): Drawable {
        val color = when {
            place.isMine -> Espresso
            place.isBabyFriendly -> Sage
            place.status(Feature.PRAM_ACCESS) == Status.YES ||
                place.status(Feature.PRAM_ACCESS) == Status.LIMITED -> Honey
            else -> Fog
        }.toArgb()
        return cache.getOrPut(color to selected) { draw(color, selected) }
    }

    private fun draw(color: Int, selected: Boolean): Drawable {
        val d = context.resources.displayMetrics.density
        // The selected pin is ~1.8x larger and sits inside a soft halo, so it stays visible in a crowd.
        val pinW = (if (selected) 46 else 26) * d
        val halo = if (selected) 10 * d else 0f
        val w = (pinW + 2 * halo).toInt()
        val h = (pinW * 1.3f + halo).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val r = pinW / 2f
        val cy = halo + r
        if (selected) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 70 }
            c.drawCircle(cx, cy, r + halo - 1 * d, haloPaint)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (selected) setShadowLayer(4 * d, 0f, 2 * d, 0x66000000)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (if (selected) 3.5f else 2.5f) * d
        }
        val path = android.graphics.Path().apply {
            addCircle(cx, cy, r - 2 * d, android.graphics.Path.Direction.CW)
            moveTo(cx - r * 0.55f, cy + r * 0.6f)
            lineTo(cx, h - 2 * d)
            lineTo(cx + r * 0.55f, cy + r * 0.6f)
            close()
        }
        c.drawPath(path, fill)
        c.drawCircle(cx, cy, r - 2 * d, stroke)
        c.drawCircle(cx, cy, r * 0.32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
        return BitmapDrawable(context.resources, bmp)
    }
}
