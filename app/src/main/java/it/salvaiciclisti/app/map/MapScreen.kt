package it.salvaiciclisti.app.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import it.salvaiciclisti.app.R
import it.salvaiciclisti.app.data.AssetRepository
import it.salvaiciclisti.app.data.DetailLayerDisplay
import it.salvaiciclisti.app.data.SettingsRepository
import it.salvaiciclisti.app.location.requestOneShotLocation
import it.salvaiciclisti.app.routing.AddressIndex
import it.salvaiciclisti.app.routing.FilteredGraph
import it.salvaiciclisti.app.routing.LabeledRoute
import it.salvaiciclisti.app.routing.LatLon
import it.salvaiciclisti.app.routing.RouteResult
import it.salvaiciclisti.app.routing.RouteRule
import it.salvaiciclisti.app.routing.RoutingEngine
import it.salvaiciclisti.app.routing.RoutingGraph
import it.salvaiciclisti.app.routing.StreetEntry
import it.salvaiciclisti.app.routing.closestPointOnSegment
import it.salvaiciclisti.app.routing.findRuledRoutes
import it.salvaiciclisti.app.routing.nearestPointTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val ROUTE_TAP_THRESHOLD_M = 40.0

// Stesso tag usato in AssetRepository.kt (dichiarato li' come privato al file, va ripetuto qui) - vedi la nota li' per il perche' di questi log temporanei di misurazione tempi.
private const val PERF_TAG = "SiCPerf"

/** Colore di riserva per un arco la cui sorgente non ha (per qualche motivo)
 * un colore configurato - non dovrebbe succedere in pratica (ogni sorgente
 * nota ha un default in SourceDisplay), ma un fallback grigio e' piu' sano
 * di un crash su una mappa mancante. */
private const val DEFAULT_SOURCE_COLOR = 0xFF757575.toInt()

/** CyclOSM (tile-cyclosm.openstreetmap.fr), lo stile dedicato al ciclismo
 * ospitato dal capitolo francese di OSM - vedi [MapStyle] e [tileSourceFor].
 * Nessuna chiave richiesta (fair-use policy OSMF, come MAPNIK), a differenza
 * di CartoDB (API key obbligatoria) e di Wikimedia (i tile pubblici di
 * maps.wikimedia.org rispondono 403 fuori dai siti Wikimedia/affiliati - non
 * utilizzabili dalla nostra app, nonostante la doc precedente in
 * MapStyle.kt). */
private val CYCLOSM_TILE_SOURCE: ITileSource = XYTileSource(
    "CyclOSM",
    1,
    20,
    256,
    ".png",
    arrayOf(
        "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
        "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
        "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/",
    ),
    "Map data © OpenStreetMap contributors, Tiles style by CyclOSM",
)

/** Sorgente di tile osmdroid per lo stile scelto in Impostazioni. */
private fun tileSourceFor(style: MapStyle): ITileSource = when (style) {
    MapStyle.MAPNIK -> TileSourceFactory.MAPNIK
    MapStyle.CYCLOSM -> CYCLOSM_TILE_SOURCE
}

/** Icona a pallino per i marker dei layer di dettaglio (bike box,
 * rastrelliere, punti critici...) - un solo Bitmap condiviso per ciascun
 * [it.salvaiciclisti.app.routing.DetailLayer] (non uno per punto: 813 punti
 * in totale su 9 layer, ridisegnarlo per ognuno sarebbe lavoro ripetuto
 * inutile). Pallino nel colore della sorgente (Impostazioni), bordo bianco
 * per restare leggibile su qualunque sfondo, simbolo del layer (vedi
 * [DetailLayerDisplay]) centrato in bianco. Sostituisce il precedente
 * setTextIcon(symbol), che non applicava ne' colore ne' dimensione - da li'
 * le "croci" tutte uguali indipendentemente dalla sorgente, troppo piccole
 * perche' il colore si vedesse (screenshot di Alessandro, 2026-10-02). */
private fun detailLayerIcon(context: Context, color: Int, symbol: String): BitmapDrawable {
    val sizePx = 56
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = sizePx / 2f

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(radius, radius, radius - 2f, fillPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(radius, radius, radius - 2f, borderPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sizePx * 0.5f
    }
    val textY = radius - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(symbol, radius, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

/** Tratteggio per i layer di dettaglio che NON sono piste ciclabili vere
 * (vedi [it.salvaiciclisti.app.routing.DetailLayer.dash], valorizzato solo
 * per i layer elencati in NON_CICLABILE_LAYERS in build_routing_graph.py) -
 * null (tratto continuo, il default di Polyline) per i percorsi veri e per
 * i punti, un pattern diverso per ciascuna categoria non ciclabile cosi'
 * non si confondono visivamente con un percorso vero della stessa
 * sorgente/colore (Alessandro, 2026-10-02: "usa -- per i treni, -. per i
 * confini, insomma tutti i tratteggi possibili escluso quello continuo"). */
private fun dashEffectFor(dash: String?): PathEffect? = when (dash) {
    null -> null
    "dash" -> DashPathEffect(floatArrayOf(24f, 14f), 0f)
    "dashdot" -> DashPathEffect(floatArrayOf(24f, 12f, 4f, 12f), 0f)
    "dot" -> DashPathEffect(floatArrayOf(4f, 10f), 0f)
    else -> DashPathEffect(floatArrayOf(10f, 10f), 0f) // categoria futura non ancora assegnata ad un pattern suo
}

/**
 * Everything about a search that must survive a detour to the route detail
 * (or Naviga) screen and back - hoisted out of [MapScreen] itself and kept
 * alive by whoever hosts the screen (MainActivity's own state, same "survive
 * a detour" pattern GTL uses for its own cross-screen state), since a plain
 * `remember` inside MapScreen is torn down the moment the app's `when`
 * dispatch switches away from it. Without this, "Indietro" from the route
 * detail screen came back to a blank map - same search fields, same
 * partenza/arrivo, but the just-computed routes gone.
 */
class MapSearchState {
    var startText by mutableStateOf("")
    var endText by mutableStateOf("")
    // An explicit search/tap choice always wins over the live GPS point -
    // MapScreen's own resolvedStart picks whichever applies.
    var startOverride by mutableStateOf<LatLon?>(null)
    var endPoint by mutableStateOf<LatLon?>(null)
    var routes by mutableStateOf<List<LabeledRoute>>(emptyList())
}

private fun ruleDisplayName(rule: RouteRule): String = when (rule) {
    RouteRule.BUDGET_CAP -> "Budget"
    RouteRule.SOURCE_SURCHARGE -> "Supplemento"
    RouteRule.DIRECTIONAL -> "Direzionale"
}

/**
 * The app's main screen: search-by-street or tap-to-set-arrival, live GPS
 * as the default partenza, fino a 6 percorsi (2026-10-02: tre regole di
 * preferenza SIC x [SettingsRepository.getRoutesPerRule], vedi RoutingRules.kt)
 * ciascuno colorato segmento per segmento secondo la sorgente che lo fornisce
 * (vedi Impostazioni per i colori) ed etichettato (1)(2)(3)... sulla mappa e
 * nel riepilogo in basso - toccare un'etichetta o il percorso stesso apre il
 * dettaglio ([onRouteSelected]), calcolati sulle sorgenti abilitate in
 * Impostazioni.
 *
 * (2026-10-02: pulsante tondo "layers" in basso a destra - vedi
 * [it.salvaiciclisti.app.routing.DetailLayer] - apre un pannello per
 * scegliere quali layer di dettaglio SIC mostrare sulla mappa - bike box,
 * rastrelliere, punti critici, passaggi segreti, ecc. - indipendentemente
 * dalle sorgenti abilitate per il routing: puntini/tracciati disegnati con
 * lo stesso colore della sorgente e un simbolo diverso per layer, vedi
 * [DetailLayerDisplay]. Lo stesso pannello ha anche una sezione "Rete
 * completa" per disegnare tutti i tratti di una sorgente abilitata, non
 * persistita - vedi networkOverlaySources piu' sotto.)
 *
 * (2026-10-02: stile della mappa di base scelto in Impostazioni - vedi
 * [MapStyle] e [tileSourceFor] - applicato una sola volta alla creazione di
 * mapView, come le altre impostazioni lette qui sotto via remember{}: un
 * cambio richiede di tornare sulla schermata mappa da zero, stesso
 * comportamento gia' in uso per motore di routing/sorgenti abilitate/colori.)
 */
@Composable
fun MapScreen(state: MapSearchState, onRouteSelected: (RouteResult) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // (2026-09-19: t0 e i Log.d(PERF_TAG, ...) sotto sono temporanei, per
    // misurare da logcat dove va il tempo all'avvio/al ritorno sulla mappa -
    // vedi la stessa nota in AssetRepository.kt. Da togliere una volta capito.)
    val t0 = remember {
        Log.d(PERF_TAG, "MapScreen: composizione (ri)avviata")
        System.currentTimeMillis()
    }
    val addressIndex = remember { AssetRepository.getAddressIndex(context) }
    val graph = remember { AssetRepository.getGraph(context) }
    val sources = remember { AssetRepository.getSources(context) }
    val sourceColors = remember { sources.associate { it.key to SettingsRepository.getSourceColor(context, it.key) } }
    val enabledSources = remember { SettingsRepository.getEnabledSources(context, sources.map { it.key }) }
    val engine = remember { SettingsRepository.getRoutingEngine(context) }
    val routesPerRule = remember { SettingsRepository.getRoutesPerRule(context) }
    val ruleParams = remember { SettingsRepository.getRuleParams(context) }
    val filteredGraph = remember(enabledSources) {
        val t1 = System.currentTimeMillis()
        FilteredGraph(graph, enabledSources).also {
            Log.d(
                PERF_TAG,
                "FilteredGraph: ${System.currentTimeMillis() - t1} ms, ${it.activeEdgeIds.size} archi attivi " +
                    "(t=${System.currentTimeMillis() - t0} ms da inizio MapScreen)",
            )
        }
    }

    // Layer di dettaglio SIC (bike box, rastrelliere, punti critici,
    // passaggi segreti...) - vedi doc del pulsante "layers" sopra. Lista
    // vuota se layers.json non e' ancora disponibile (Alessandro non ha
    // ancora ripubblicato, o l'asset facoltativo non e' stato scaricato).
    val detailLayers = remember { AssetRepository.getDetailLayers(context) }
    var enabledDetailLayers by remember {
        mutableStateOf(SettingsRepository.getEnabledDetailLayers(context, detailLayers.map { it.key }))
    }
    var layersPanelOpen by remember { mutableStateOf(false) }

    // Rete completa per sorgente (2026-10-02, richiesta di Alessandro: "se
    // voglio visualizzare prima di tutto le rotte disponibili, e farmi un
    // percorso a occhio, voglio poterlo fare") - disegna OGNI tratto attivo
    // di una sorgente abilitata, indipendentemente da qualunque percorso
    // calcolato. Deliberatamente NON persistita (si azzera ad ogni apertura
    // della schermata): puo' disegnare migliaia di segmenti (OSM da solo ha
    // ~7600 archi), pesante da lasciare sempre accesa senza accorgersene.
    var networkOverlaySources by remember { mutableStateOf<Set<String>>(emptySet()) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var currentGpsPoint by remember { mutableStateOf<LatLon?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasLocationPermission = granted
        if (granted) requestOneShotLocation(context) { loc -> currentGpsPoint = loc }
    }
    LaunchedEffect(Unit) {
        if (hasLocationPermission) {
            requestOneShotLocation(context) { loc -> currentGpsPoint = loc }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // An explicit search/tap choice always wins over the live GPS point -
    // resolvedStart below picks whichever applies.
    val resolvedStart = state.startOverride ?: currentGpsPoint

    var startSuggestions by remember { mutableStateOf<List<StreetEntry>>(emptyList()) }
    var endSuggestions by remember { mutableStateOf<List<StreetEntry>>(emptyList()) }

    var pendingTapPoint by remember { mutableStateOf<LatLon?>(null) }

    // Recomputes as soon as both ends are known ("appena ha l'arrivo, traccia
    // il percorso migliore") e di nuovo se cambiano sorgenti abilitate,
    // motore, numero di percorsi per regola o parametri delle regole
    // (Impostazioni). Keyed on values, quindi tornare qui con la stessa
    // partenza/arrivo - es. dopo "Indietro" dal dettaglio percorso - non
    // ricalcola nulla, lascia state.routes (gia' hoisted, vedi
    // MapSearchState) com'erano.
    LaunchedEffect(resolvedStart, state.endPoint, enabledSources, engine, routesPerRule, ruleParams) {
        val start = resolvedStart
        val end = state.endPoint
        val tSearch0 = System.currentTimeMillis()
        state.routes = if (start != null && end != null) {
            findRuledRoutes(filteredGraph, start, end, engine, routesPerRule, ruleParams)
        } else {
            emptyList()
        }
        Log.d(
            PERF_TAG,
            "ricerca percorso: ${System.currentTimeMillis() - tSearch0} ms, partenza=${start != null} " +
                "arrivo=${end != null}, risultati=${state.routes.size} (t=${System.currentTimeMillis() - t0} ms da inizio MapScreen)",
        )
    }

    val mapStyle = remember { SettingsRepository.getMapStyle(context) }
    val mapView = remember {
        ensureOsmdroidConfigured(context)
        MapView(context).apply {
            setTileSource(tileSourceFor(mapStyle))
            setMultiTouchControls(true)
            setScrollableAreaLimitDouble(ROMA_METROPOLITANA_BBOX)
            minZoomLevel = 9.0
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(ROMA_CENTER_LAT, ROMA_CENTER_LON))
        }
    }
    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    // Tap handling needs the LATEST routes/callback without recreating the
    // MapEventsOverlay on every recomposition - rememberUpdatedState keeps
    // one stable overlay instance that always reads the current values.
    val routesRef = rememberUpdatedState(state.routes)
    val onRouteSelectedRef = rememberUpdatedState(onRouteSelected)

    LaunchedEffect(mapView) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val tapped = LatLon(p.latitude, p.longitude)
                val tappedRoute = routesRef.value.firstOrNull { labeled ->
                    labeled.result.steps.any { step ->
                        step.geom.zipWithNext().any { (a, b) ->
                            closestPointOnSegment(tapped, a, b).distanceM <= ROUTE_TAP_THRESHOLD_M
                        }
                    }
                }
                if (tappedRoute != null) {
                    onRouteSelectedRef.value(tappedRoute.result)
                } else {
                    pendingTapPoint = tapped
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        // Inserted at the bottom of the overlay stack so it never blocks
        // taps on markers drawn above it.
        mapView.overlays.add(0, MapEventsOverlay(receiver))
    }

    var routePolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var routeLabelMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    LaunchedEffect(mapView, state.routes) {
        routePolylines.forEach { mapView.overlays.remove(it) }
        routeLabelMarkers.forEach { mapView.overlays.remove(it) }

        // Un Polyline per OGNI TRATTO (non piu' uno per percorso intero): il
        // colore segue la sorgente che fornisce quel tratto specifico
        // (verde SIC, rosso OSM, ecc. - Impostazioni), non piu' quale
        // percorso alternativo e'. Il primo percorso prodotto resta un filo
        // piu' spesso, puramente per leggibilita' quando piu' percorsi si
        // sovrappongono.
        //
        // (2026-10-02, Alessandro: con "Rete completa" accesa i percorsi
        // suggeriti sparivano dentro lo sfondo dello stesso colore - sorgente
        // SIC verde sia per lo sfondo che per il percorso. Ogni tratto
        // suggerito prende percio' un "bordo" bianco: un Polyline bianco piu'
        // largo sotto, uno colorato sopra, stesso trucco cartografico delle
        // strade con casing. TUTTI i bordi bianchi vanno aggiunti a
        // mapView.overlays PRIMA di tutti i tratti colorati (non intrecciati
        // step per step), altrimenti il bordo bianco di un percorso
        // disegnato dopo coprirebbe il colore di un percorso disegnato prima
        // nei punti in cui si sovrappongono.
        val routeCasings = mutableListOf<Polyline>()
        val routeFills = mutableListOf<Polyline>()
        state.routes.forEachIndexed { routeIndex, labeled ->
            val width = if (routeIndex == 0) 10f else 6f
            labeled.result.steps.forEach { step ->
                val points = step.geom.map { GeoPoint(it.lat, it.lon) }
                routeCasings += Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = AndroidColor.WHITE
                    outlinePaint.strokeWidth = width + 5f
                }
                routeFills += Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = sourceColors[step.source] ?: DEFAULT_SOURCE_COLOR
                    outlinePaint.strokeWidth = width
                }
            }
        }
        routePolylines = routeCasings + routeFills
        routePolylines.forEach { mapView.overlays.add(it) }

        // Un marker numerato (1)(2)(3)... a meta' di ciascun percorso -
        // setTextIcon e' un'icona "a bolla" gia' pronta di osmdroid, niente
        // disegno a mano di bitmap. Cliccabile come scorciatoia per aprire
        // il dettaglio, in aggiunta al tocco sul tracciato stesso.
        routeLabelMarkers = state.routes.mapNotNull { labeled ->
            val flat = labeled.result.steps.flatMap { it.geom }
            if (flat.isEmpty()) return@mapNotNull null
            val mid = flat[flat.size / 2]
            Marker(mapView).apply {
                position = GeoPoint(mid.lat, mid.lon)
                setTextIcon("${labeled.label}")
                title = "${ruleDisplayName(labeled.rule)}: %.1f km".format(labeled.result.totalLengthM / 1000.0)
                setOnMarkerClickListener { _, _ -> onRouteSelectedRef.value(labeled.result); true }
            }
        }
        routeLabelMarkers.forEach { mapView.overlays.add(it) }

        mapView.invalidate()
    }

    // Layer di dettaglio abilitati (bike box, rastrelliere, punti critici,
    // passaggi segreti...) - indipendenti dai percorsi/sorgenti di routing,
    // disegnati/rimossi ogni volta che cambia la selezione nel pannello
    // "layers" o la lista stessa (es. dopo un aggiornamento dati). Punti come
    // marker testuali (stesso setTextIcon gia' usato sopra per le etichette
    // dei percorsi, un simbolo diverso per layer - vedi DetailLayerDisplay);
    // tracciati (es. Passaggi_Segreti) come Polyline nel colore della
    // sorgente, stessa API gia' usata per i tratti di percorso.
    var detailPointMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    var detailLineOverlays by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    LaunchedEffect(mapView, detailLayers, enabledDetailLayers, sourceColors) {
        detailPointMarkers.forEach { mapView.overlays.remove(it) }
        detailLineOverlays.forEach { mapView.overlays.remove(it) }

        // Anche se enabledDetailLayers (persistito) contenesse ancora la
        // chiave di un layer la cui sorgente e' stata nel frattempo
        // disabilitata in Impostazioni, qui non va comunque disegnato -
        // stessa regola del pannello "layers" sotto (Alessandro, 2026-10-02).
        val activeLayers = detailLayers.filter { it.key in enabledDetailLayers && it.source in enabledSources }

        detailPointMarkers = activeLayers.flatMap { layer ->
            val symbol = DetailLayerDisplay.symbolFor(layer.key)
            val color = sourceColors[layer.source] ?: DEFAULT_SOURCE_COLOR
            // Un'icona costruita una volta sola per layer (non per punto,
            // vedi detailLayerIcon), condivisa da tutti i suoi marker.
            val icon = detailLayerIcon(context, color, symbol)
            layer.points.map { point ->
                Marker(mapView).apply {
                    position = GeoPoint(point.point.lat, point.point.lon)
                    setIcon(icon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = point.name ?: layer.label
                    snippet = layer.label
                }
            }
        }
        detailPointMarkers.forEach { mapView.overlays.add(it) }

        detailLineOverlays = activeLayers.flatMap { layer ->
            val color = sourceColors[layer.source] ?: DEFAULT_SOURCE_COLOR
            val dash = dashEffectFor(layer.dash)
            layer.lines.map { line ->
                Polyline().apply {
                    setPoints(line.geom.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = color
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.pathEffect = dash
                }
            }
        }
        detailLineOverlays.forEach { mapView.overlays.add(it) }

        mapView.invalidate()
    }

    // Rete completa per sorgente (vedi networkOverlaySources sopra): un
    // Polyline per OGNI arco attivo delle sole sorgenti selezionate nel
    // pannello "layers", indipendente da qualunque percorso calcolato.
    // Riusa filteredGraph (gia' calcolato per il routing) invece di
    // ricostruire qualcosa di nuovo.
    var networkOverlayPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    LaunchedEffect(mapView, filteredGraph, networkOverlaySources, sourceColors) {
        networkOverlayPolylines.forEach { mapView.overlays.remove(it) }

        networkOverlayPolylines = filteredGraph.activeEdgeIds
            .map { filteredGraph.graph.edges[it] }
            .filter { it.source in networkOverlaySources }
            .map { edge ->
                Polyline().apply {
                    setPoints(edge.geom.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.color = sourceColors[edge.source] ?: DEFAULT_SOURCE_COLOR
                    outlinePaint.strokeWidth = 4f
                }
            }
        networkOverlayPolylines.forEach { mapView.overlays.add(it) }

        mapView.invalidate()
    }

    var gpsMarker by remember { mutableStateOf<Marker?>(null) }
    LaunchedEffect(mapView, currentGpsPoint) {
        gpsMarker?.let { mapView.overlays.remove(it) }
        gpsMarker = currentGpsPoint?.let { point ->
            Marker(mapView).apply {
                position = GeoPoint(point.lat, point.lon)
                title = "Posizione attuale"
            }.also { mapView.overlays.add(it) }
        }
        mapView.invalidate()
    }

    var endMarker by remember { mutableStateOf<Marker?>(null) }
    LaunchedEffect(mapView, state.endPoint) {
        endMarker?.let { mapView.overlays.remove(it) }
        endMarker = state.endPoint?.let { point ->
            Marker(mapView).apply {
                position = GeoPoint(point.lat, point.lon)
                title = "Arrivo"
            }.also { mapView.overlays.add(it) }
        }
        mapView.invalidate()
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .padding(8.dp),
        ) {
            OutlinedTextField(
                value = state.startText,
                onValueChange = { text ->
                    state.startText = text
                    startSuggestions = addressIndex?.search(text) ?: emptyList()
                },
                label = {
                    Text(
                        if (state.startOverride == null && currentGpsPoint != null) {
                            "Partenza (posizione attuale)"
                        } else {
                            "Partenza"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SuggestionsDropdown(startSuggestions) { entry ->
                state.startOverride = entry.nearestPointTo(state.endPoint ?: entry.points.first())
                state.startText = entry.name
                startSuggestions = emptyList()
            }

            OutlinedTextField(
                value = state.endText,
                onValueChange = { text ->
                    state.endText = text
                    endSuggestions = addressIndex?.search(text) ?: emptyList()
                },
                label = { Text("Arrivo") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            SuggestionsDropdown(endSuggestions) { entry ->
                state.endPoint = entry.nearestPointTo(resolvedStart ?: entry.points.first())
                state.endText = entry.name
                endSuggestions = emptyList()
            }

            if (addressIndex == null) {
                Text(
                    text = "Ricerca indirizzi non disponibile (indice non ancora generato da precompila).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // Pulsante tondo "layers" - apre il pannello di scelta dei layer di
        // dettaglio (vedi doc di MapScreen sopra). In basso a destra, stesso
        // angolo in cui lo mettono le app di navigazione a cui Alessandro si
        // e' ispirato (il riepilogo percorsi sotto e' centrato, lascia
        // libero questo angolo).
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp)
                .clickable { layersPanelOpen = true },
            shape = CircleShape,
            tonalElevation = 6.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(R.drawable.ic_layers), contentDescription = "Layer sulla mappa")
            }
        }

        if (state.routes.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    state.routes.forEach { labeled ->
                        val bySource = labeled.result.steps
                            .groupBy { it.source }
                            .mapValues { (_, steps) -> steps.sumOf { it.lengthM } }
                        Column(
                            modifier = Modifier
                                .clickable { onRouteSelected(labeled.result) }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = "(%d) %s: %.1f km".format(
                                    labeled.label,
                                    ruleDisplayName(labeled.rule),
                                    labeled.result.totalLengthM / 1000.0,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                bySource.forEach { (sourceKey, lengthM) ->
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(Color(sourceColors[sourceKey] ?: DEFAULT_SOURCE_COLOR)),
                                    )
                                    Text(
                                        text = " %.1fkm  ".format(lengthM / 1000.0),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingTapPoint?.let { point ->
        AlertDialog(
            onDismissRequest = { pendingTapPoint = null },
            title = { Text("Punto selezionato") },
            text = { Text("Impostare questo punto come arrivo?") },
            confirmButton = {
                TextButton(onClick = {
                    state.endPoint = point
                    state.endText = "Punto sulla mappa"
                    pendingTapPoint = null
                }) { Text("Imposta come arrivo") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTapPoint = null }) { Text("Annulla") }
            },
        )
    }

    if (layersPanelOpen) {
        AlertDialog(
            onDismissRequest = { layersPanelOpen = false },
            title = { Text("Layer sulla mappa") },
            text = {
                Column {
                    // Solo i layer la cui sorgente e' abilitata al routing in
                    // Impostazioni ("Sorgenti usate per calcolare i percorsi")
                    // - 2026-10-02, Alessandro: se una sorgente e' spenta li',
                    // qui non deve nemmeno essere possibile selezionare i suoi
                    // layer (es. "treni" di una G3W disabilitata).
                    val visibleDetailLayers = detailLayers.filter { it.source in enabledSources }
                    if (visibleDetailLayers.isEmpty()) {
                        Text(
                            text = "Nessun layer di dettaglio disponibile (sorgente spenta in Impostazioni, o dati non ancora pubblicati).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    visibleDetailLayers.forEach { layer ->
                        val checked = layer.key in enabledDetailLayers
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    enabledDetailLayers = if (checked) {
                                        enabledDetailLayers - layer.key
                                    } else {
                                        enabledDetailLayers + layer.key
                                    }
                                    SettingsRepository.setEnabledDetailLayers(context, enabledDetailLayers)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                text = "${DetailLayerDisplay.symbolFor(layer.key)}  ${layer.label}",
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }

                    if (enabledSources.isNotEmpty()) {
                        Text(
                            text = "Rete completa",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                        )
                        Text(
                            text = "Mostra tutti i tratti di una sorgente abilitata, per farsi un " +
                                "percorso a occhio prima di cercare. Si azzera ogni volta che si riapre la mappa.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        sources.filter { it.key in enabledSources }.forEach { source ->
                            val checked = source.key in networkOverlaySources
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        networkOverlaySources = if (checked) {
                                            networkOverlaySources - source.key
                                        } else {
                                            networkOverlaySources + source.key
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Box(
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(10.dp)
                                        .background(Color(sourceColors[source.key] ?: DEFAULT_SOURCE_COLOR)),
                                )
                                Text(text = "  ${source.label}", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { layersPanelOpen = false }) { Text("Chiudi") }
            },
        )
    }
}

@Composable
private fun SuggestionsDropdown(suggestions: List<StreetEntry>, onSelect: (StreetEntry) -> Unit) {
    if (suggestions.isEmpty()) return
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            suggestions.take(6).forEach { entry ->
                Text(
                    text = entry.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry) }
                        .padding(8.dp),
                )
            }
        }
    }
}
