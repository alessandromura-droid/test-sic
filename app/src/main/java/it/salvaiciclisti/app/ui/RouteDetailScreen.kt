package it.salvaiciclisti.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.salvaiciclisti.app.routing.RouteInstruction
import it.salvaiciclisti.app.routing.RouteResult
import it.salvaiciclisti.app.routing.TurnType
import it.salvaiciclisti.app.routing.buildInstructions
import it.salvaiciclisti.app.routing.routeAscentDescent
import it.salvaiciclisti.app.routing.routeElevationProfile
import it.salvaiciclisti.app.routing.routeToGpx

private fun turnLabel(turn: TurnType?): String? = when (turn) {
    null -> null
    TurnType.DRITTO -> "prosegui dritto su"
    TurnType.LEGGERA_SINISTRA -> "leggermente a sinistra su"
    TurnType.SINISTRA -> "svolta a sinistra su"
    TurnType.NETTA_SINISTRA -> "svolta decisa a sinistra su"
    TurnType.LEGGERA_DESTRA -> "leggermente a destra su"
    TurnType.DESTRA -> "svolta a destra su"
    TurnType.NETTA_DESTRA -> "svolta decisa a destra su"
    TurnType.INVERSIONE -> "inversione a U su"
}

/**
 * The description opened by tapping a route on the map: total distance up
 * top, then one line per named stretch with the turn onto it, and a fixed
 * "Naviga" button at the bottom that hands the same [route] off to
 * [onNavigate].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(route: RouteResult, onBack: () -> Unit, onNavigate: (RouteResult) -> Unit) {
    val context = LocalContext.current
    val instructions = remember(route) { buildInstructions(route) }
    val profile = remember(route) { routeElevationProfile(route) }
    val ascentDescent = remember(route) { routeAscentDescent(route) }
    // Storage Access Framework, not a share sheet: the point of this export
    // is to open the track in a real GIS/GPX tool to see exactly what the
    // router did, so "save this file somewhere I choose" is what's needed,
    // not "hand it to another app". No extra permission or FileProvider
    // required for this contract.
    val exportGpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(routeToGpx(route).toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("%.1f km".format(route.totalLengthM / 1000.0)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Indietro") }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedButton(
                    onClick = { exportGpxLauncher.launch("percorso.gpx") },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("Esporta GPX")
                }
                Button(
                    onClick = { onNavigate(route) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
                ) {
                    Text("Naviga")
                }
            }
        },
    ) { padding: PaddingValues ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            // (2026-10-14) Profilo altimetrico, se il grafo ha le quote.
            if (profile.size >= 2) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val zMin = profile.minOf { it.second }
                        val zMax = profile.maxOf { it.second }
                        Text(
                            text = "Salita %.0f m · Discesa %.0f m · quota %.0f–%.0f m".format(
                                ascentDescent.first, ascentDescent.second, zMin, zMax,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ElevationProfile(profile, modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
                    }
                    Divider()
                }
            }
            items(instructions) { instruction: RouteInstruction ->
                InstructionRow(instruction)
                Divider()
            }
        }
    }
}

@Composable
private fun InstructionRow(instruction: RouteInstruction) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val turnText = turnLabel(instruction.turn)
        if (turnText != null) {
            Text(
                text = "$turnText ${instruction.streetName}",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(text = instruction.streetName, style = MaterialTheme.typography.bodyLarge)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            val distanceText = if (instruction.distanceM >= 1000) {
                "%.1f km".format(instruction.distanceM / 1000.0)
            } else {
                "%.0f m".format(instruction.distanceM)
            }
            Text(text = distanceText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Profilo altimetrico semplice: quota (asse verticale, scala min-max con
 * almeno 10 m di escursione, cosi' a Roma non si esagerano colline di pochi
 * metri) in funzione della distanza percorsa. */
@Composable
private fun ElevationProfile(profile: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = Color.Gray
    Canvas(modifier = modifier) {
        val dMax = profile.last().first.coerceAtLeast(1.0)
        val zMin = profile.minOf { it.second }
        val zMaxRaw = profile.maxOf { it.second }
        val zMax = if (zMaxRaw - zMin < 10.0) zMin + 10.0 else zMaxRaw
        fun x(d: Double) = (d / dMax * size.width).toFloat()
        fun y(z: Double) = (size.height - (z - zMin) / (zMax - zMin) * size.height).toFloat()
        val path = Path()
        profile.forEachIndexed { k, (d, z) ->
            if (k == 0) path.moveTo(x(d), y(z)) else path.lineTo(x(d), y(z))
        }
        drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        drawPath(path, lineColor, style = Stroke(width = 3f))
    }
}
