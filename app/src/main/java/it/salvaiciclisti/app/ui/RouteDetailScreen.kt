package it.salvaiciclisti.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.salvaiciclisti.app.routing.RouteInstruction
import it.salvaiciclisti.app.routing.RouteResult
import it.salvaiciclisti.app.routing.TurnType
import it.salvaiciclisti.app.routing.buildInstructions
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
