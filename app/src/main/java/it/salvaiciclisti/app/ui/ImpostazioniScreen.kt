package it.salvaiciclisti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.salvaiciclisti.app.data.MAX_NUMERO_PERCORSI
import it.salvaiciclisti.app.data.PALETTE_COLORI
import it.salvaiciclisti.app.data.SettingsRepository
import it.salvaiciclisti.app.data.TrackStyle
import it.salvaiciclisti.app.map.MapStyle
import it.salvaiciclisti.app.routing.AppRoutingParams
import it.salvaiciclisti.app.routing.RoutingEngine

/** Quale colore si sta scegliendo: stile [index] (0 = rete sulla mappa,
 * 1..N = percorso), tratto SIC o raccordi. */
private data class ColorTarget(val index: Int, val sic: Boolean)

/**
 * Nuova pagina Impostazioni (Alessandro, 2026-10-14). Le sorgenti NON sono
 * piu' sceglibili: l'app usa sempre tutte quelle del grafo pubblicato (SIC +
 * raccordi). Si scelgono solo:
 * - motore di calcolo, numero di percorsi alternativi N, pesi di SIC e
 *   raccordi, peso della distanza e della salita, margine massimo sul piu'
 *   breve e arretramento massimo (vedi AppRoutingParams in AppRouting.kt);
 * - aggiornamento automatico dei dati mappa;
 * - mappa di base (OSM, CyclOSM, nessuna);
 * - colori e larghezze (dp) di SIC e raccordi per la rete sulla mappa e per
 *   ciascuno degli N percorsi (2N + 2 colori e larghezze).
 * La vecchia pagina (SettingsScreen.kt) resta nel codice come ramo secco, non
 * piu' raggiungibile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpostazioniScreen() {
    val context = LocalContext.current
    var params by remember { mutableStateOf(SettingsRepository.getAppRoutingParams(context)) }
    var autoUpdate by remember { mutableStateOf(SettingsRepository.getAutoUpdateMapData(context)) }
    var mapStyle by remember { mutableStateOf(SettingsRepository.getMapStyle(context)) }
    var styles by remember {
        mutableStateOf((0..MAX_NUMERO_PERCORSI).associateWith { SettingsRepository.getTrackStyle(context, it) })
    }
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }

    fun updateParams(update: (AppRoutingParams) -> AppRoutingParams) {
        val updated = update(params)
        params = updated
        SettingsRepository.setAppRoutingParams(context, updated)
    }

    fun updateStyle(index: Int, update: (TrackStyle) -> TrackStyle) {
        val updated = update(styles.getValue(index))
        styles = styles + (index to updated)
        SettingsRepository.setTrackStyle(context, index, updated)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Impostazioni") }) }) { padding: PaddingValues ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            item { SectionTitle("Calcolo del percorso") }
            item {
                listOf(
                    RoutingEngine.JGRAPHT to "Dijkstra (libreria JGraphT)",
                    RoutingEngine.IN_HOUSE to "Dijkstra in-house",
                ).forEach { (candidate, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = params.engine == candidate,
                            onClick = { updateParams { it.copy(engine = candidate) } },
                        )
                        Text(label)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Numero di percorsi alternativi", modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        updateParams { it.copy(numeroPercorsi = (it.numeroPercorsi - 1).coerceIn(1, MAX_NUMERO_PERCORSI)) }
                    }) { Text("-") }
                    Text("${params.numeroPercorsi}")
                    IconButton(onClick = {
                        updateParams { it.copy(numeroPercorsi = (it.numeroPercorsi + 1).coerceIn(1, MAX_NUMERO_PERCORSI)) }
                    }) { Text("+") }
                }
            }
            item {
                NumberField("Peso dei tratti SIC (moltiplica la lunghezza)", params.pesoSic) { v ->
                    updateParams { it.copy(pesoSic = v) }
                }
            }
            item {
                NumberField("Peso dei raccordi (moltiplica la lunghezza)", params.pesoRaccordi) { v ->
                    updateParams { it.copy(pesoRaccordi = v) }
                }
            }
            item {
                NumberField("Peso della Ciclopolitana mancante", params.pesoCiclopolitana) { v ->
                    updateParams { it.copy(pesoCiclopolitana = v) }
                }
            }
            item {
                NumberField("Peso dei percorsi nel verde", params.pesoVerde) { v ->
                    updateParams { it.copy(pesoVerde = v) }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Evita parchi (chiusi la notte)", modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Switch(checked = params.evitaParchi, onCheckedChange = { checked ->
                        updateParams { it.copy(evitaParchi = checked) }
                    })
                }
            }
            item {
                NumberField("Peso delle strade scolastiche", params.pesoScolastiche) { v ->
                    updateParams { it.copy(pesoScolastiche = v) }
                }
            }
            item {
                NumberField("Peso dei cantieri", params.pesoCantieri) { v ->
                    updateParams { it.copy(pesoCantieri = v) }
                }
            }
            item {
                NumberField("Peso delle piste rimosse", params.pesoRimosse) { v ->
                    updateParams { it.copy(pesoRimosse = v) }
                }
            }
            item {
                NumberField("Peso della distanza", params.pesoKm) { v -> updateParams { it.copy(pesoKm = v) } }
            }
            item {
                NumberField("Peso della salita (metri di piano per 1 m di salita)", params.pesoSalita) { v ->
                    updateParams { it.copy(pesoSalita = v) }
                }
            }
            item {
                NumberField("Margine massimo sul percorso più breve (%)", params.margineMassimoPercent) { v ->
                    updateParams { it.copy(margineMassimoPercent = v) }
                }
            }
            item {
                NumberField("Arretramento massimo rispetto alla meta (%)", params.arretramentoMassimoPercent) { v ->
                    updateParams { it.copy(arretramentoMassimoPercent = v) }
                }
            }

            item { SectionTitle("Dati mappa") }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Aggiorna automaticamente i dati mappa quando disponibili",
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = autoUpdate, onCheckedChange = { checked ->
                        autoUpdate = checked
                        SettingsRepository.setAutoUpdateMapData(context, checked)
                    })
                }
            }

            item { SectionTitle("Mappa di base") }
            item {
                listOf(
                    MapStyle.MAPNIK to "OpenStreetMap",
                    MapStyle.CYCLOSM to "CyclOSM",
                    MapStyle.NESSUNA to "Nessuna mappa",
                ).forEach { (candidate, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mapStyle == candidate, onClick = {
                            mapStyle = candidate
                            SettingsRepository.setMapStyle(context, candidate)
                        })
                        Text(label)
                    }
                }
            }

            item { SectionTitle("Colori e larghezze (dp)") }
            item {
                StyleEditor("Rete sulla mappa", styles.getValue(0), onColor = { sic -> colorTarget = ColorTarget(0, sic) }) {
                    updateStyle(0, it)
                }
            }
            items(params.numeroPercorsi) { i ->
                val index = i + 1
                StyleEditor("Percorso $index", styles.getValue(index), onColor = { sic -> colorTarget = ColorTarget(index, sic) }) {
                    updateStyle(index, it)
                }
            }

            item { SectionTitle("Fonti") }
            item {
                Text(
                    "Quote: TINITALY 1.1, Tarquini S., Isola I., Favalli M., Battistini A., Dotta G. (2023), " +
                        "INGV, doi:10.13127/tinitaly/1.1 (CC BY 4.0). Mappa: © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    colorTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { colorTarget = null },
            title = { Text(if (target.sic) "Colore SIC" else "Colore raccordi") },
            text = {
                Column {
                    PALETTE_COLORI.forEach { (name, argb) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    updateStyle(target.index) {
                                        if (target.sic) it.copy(sicColor = argb) else it.copy(raccordiColor = argb)
                                    }
                                    colorTarget = null
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Swatch(argb)
                            Text("  $name")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colorTarget = null }) { Text("Chiudi") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Swatch(argb: Int, onClick: (() -> Unit)? = null) {
    val base = Modifier
        .size(24.dp)
        .clip(CircleShape)
        .background(Color(argb))
        .border(1.dp, Color.Gray, CircleShape)
    Box(modifier = if (onClick != null) base.clickable { onClick() } else base)
}

/** Riga di uno stile: SIC (colore + larghezza) e raccordi (colore + larghezza). */
@Composable
private fun StyleEditor(
    title: String,
    style: TrackStyle,
    onColor: (sic: Boolean) -> Unit,
    onChange: ((TrackStyle) -> TrackStyle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SIC", modifier = Modifier.width(72.dp))
            Swatch(style.sicColor) { onColor(true) }
            WidthField(style.sicWidthDp) { w -> onChange { it.copy(sicWidthDp = w) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Raccordi", modifier = Modifier.width(72.dp))
            Swatch(style.raccordiColor) { onColor(false) }
            WidthField(style.raccordiWidthDp) { w -> onChange { it.copy(raccordiWidthDp = w) } }
        }
    }
}

@Composable
private fun WidthField(value: Float, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(formatNumber(value.toDouble())) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it <= 30f }?.let(onValue)
        },
        label = { Text("larghezza") },
        singleLine = true,
        modifier = Modifier.padding(start = 12.dp).width(120.dp),
    )
}

/** Campo numerico libero: input non valido o vuoto lascia il valore com'era. */
@Composable
private fun NumberField(label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(formatNumber(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 }?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
