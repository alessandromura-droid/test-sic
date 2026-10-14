package it.salvaiciclisti.app.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import it.salvaiciclisti.app.location.NavigationLocationService
import it.salvaiciclisti.app.routing.RouteResult
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private val NAVIGATE_ROUTE_COLOR = AndroidColor.parseColor("#1565C0")

/**
 * "Naviga": the single chosen [route] drawn on the map, the live GPS fix
 * tracked by [NavigationLocationService] (kept alive as a foreground service
 * so it survives the screen locking), and a banner the moment a fix strays
 * off track. [onExit] tears the service down and returns to the route
 * detail/map.
 */
@Composable
fun NavigateScreen(route: RouteResult, onExit: () -> Unit) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(requiredPermissions)
    }

    // Starts the foreground service only once permission is actually held,
    // and always stops it when this screen goes away for any reason
    // (Termina navigazione, back press, or the permission never arriving) -
    // there's no scenario where it should keep polling GPS with nothing
    // showing it.
    DisposableEffect(hasLocationPermission) {
        if (hasLocationPermission) NavigationLocationService.start(context, route)
        onDispose { NavigationLocationService.stop(context) }
    }

    val navState by NavigationLocationService.state.collectAsState()

    val mapView = remember {
        ensureOsmdroidConfigured(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setScrollableAreaLimitDouble(ROMA_METROPOLITANA_BBOX)
            minZoomLevel = 9.0
            controller.setZoom(17.0)
            val first = route.steps.firstOrNull()?.geom?.firstOrNull()
            controller.setCenter(
                if (first != null) GeoPoint(first.lat, first.lon) else GeoPoint(ROMA_CENTER_LAT, ROMA_CENTER_LON),
            )
            overlays.add(
                Polyline().apply {
                    setPoints(route.steps.flatMap { it.geom }.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = NAVIGATE_ROUTE_COLOR
                    outlinePaint.strokeWidth = 10f
                },
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    var liveMarker by remember { mutableStateOf<Marker?>(null) }
    LaunchedEffect(mapView, navState.livePosition) {
        liveMarker?.let { mapView.overlays.remove(it) }
        liveMarker = navState.livePosition?.let { point ->
            Marker(mapView).apply {
                position = GeoPoint(point.lat, point.lon)
                title = "Tu"
            }.also {
                mapView.overlays.add(it)
                mapView.controller.animateTo(GeoPoint(point.lat, point.lon))
            }
        }
        mapView.invalidate()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (navState.livePosition != null && !navState.isOnTrack) {
            Surface(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = "Sei fuori percorso (%.0f m)".format(navState.distanceFromRouteM ?: 0.0),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        if (!hasLocationPermission) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                tonalElevation = 4.dp,
            ) {
                Text(
                    text = "Serve il permesso di posizione per navigare.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)) {
            navState.remainingDistanceM?.let { remaining ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    tonalElevation = 4.dp,
                ) {
                    Text(
                        text = "Rimangono %.1f km".format(remaining / 1000.0),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Button(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Termina navigazione")
            }
        }
    }
}
