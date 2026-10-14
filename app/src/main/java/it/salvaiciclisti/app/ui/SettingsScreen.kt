package it.salvaiciclisti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
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
import it.salvaiciclisti.app.data.AssetRepository
import it.salvaiciclisti.app.data.DetailLayerDisplay
import it.salvaiciclisti.app.data.SettingsRepository
import it.salvaiciclisti.app.data.SourceDisplay
import it.salvaiciclisti.app.map.MapStyle
import it.salvaiciclisti.app.routing.RoutingEngine
import it.salvaiciclisti.app.routing.RuleParams

/** I 5 colori concordati con Alessandro il 2026-10-02 (verde/rosso/blu/
 * magenta/azzurro), proposti come scelte rapide quando si tocca lo swatch di
 * una sorgente - niente color picker completo per ora, 5 scelte fisse
 * bastano e sono molto piu' semplici da offrire. */
private val COLOR_PALETTE = listOf(
    "Verde" to 0xFF2E7D32.toInt(),
    "Rosso" to 0xFFC62828.toInt(),
    "Blu" to 0xFF1565C0.toInt(),
    "Magenta" to 0xFFC800C8.toInt(),
    "Azzurro" to 0xFF0097A7.toInt(),
)

/**
 * Sorgenti in ordine fisso (SIC, OSM, poi il separatore "do not use", poi le
 * altre - vedi [SourceDisplay]), ciascuna con checkbox di partecipazione al
 * routing e uno swatch del colore usato per i suoi segmenti sulla mappa
 * (MapScreen) - toccarlo apre una scelta tra i 5 colori concordati. Sotto,
 * motore di calcolo, numero di percorsi alternativi, i parametri delle tre
 * regole (vedi RoutingRules.kt) e i dati mappa.
 *
 * (2026-10-02: ogni sorgente che ha ALMENO UN layer di dettaglio che
 * contribuisce davvero archi al grafo di routing (oggi solo Passaggi
 * segreti, sotto SIC - vedi il filtro su detailLayersBySource sotto) mostra
 * una freccina "▸"/"▾" accanto al nome: toccarla apre "a ventaglio" quei
 * layer, indentati sotto la riga della sorgente, ciascuno con la propria
 * checkbox di visibilita' sulla mappa e lo stesso simbolo usato li' (vedi
 * [DetailLayerDisplay]) - non solo SIC: i layer sono raggruppati per
 * sorgente dinamicamente, qualunque sorgente futura che ne porti (vedi
 * DETAIL_LAYER_FILES in build_routing_graph.py) comparira' qui senza
 * bisogno di toccare questo file. I layer SOLO-PUNTI (rastrelliere, bike
 * box, punti critici...) e quelli non-ciclabili (ferrovie, confini - vedi
 * NON_CICLABILE_LAYERS) non compaiono QUI (non sono archi di percorso) ma
 * restano scelgibili come al solito nel pannello "layers" sulla mappa.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val sources = remember { SourceDisplay.sortedForSettings(AssetRepository.getSources(context)) }
    val separatorIndex = remember { SourceDisplay.firstBelowSeparatorIndex(sources) }
    var enabled by remember {
        mutableStateOf(SettingsRepository.getEnabledSources(context, sources.map { it.key }))
    }
    var colors by remember {
        mutableStateOf(sources.associate { it.key to SettingsRepository.getSourceColor(context, it.key) })
    }
    var engine by remember { mutableStateOf(SettingsRepository.getRoutingEngine(context)) }
    var autoUpdateMapData by remember { mutableStateOf(SettingsRepository.getAutoUpdateMapData(context)) }
    var routesPerRule by remember { mutableStateOf(SettingsRepository.getRoutesPerRule(context)) }
    var ruleParams by remember { mutableStateOf(SettingsRepository.getRuleParams(context)) }
    var colorPickerForSource by remember { mutableStateOf<String?>(null) }

    // Layer di dettaglio raggruppati per sorgente - dinamico (vedi doc sopra):
    // qualunque chiave di sorgente compaia come [DetailLayer.source] finisce
    // sotto la riga di quella sorgente, nessun elenco scritto a mano qui.
    val detailLayers = remember { AssetRepository.getDetailLayers(context) }
    // Qui (a differenza del pannello "layers" sulla mappa, che mostra TUTTI i
    // layer di una sorgente) contano solo quelli che contribuiscono davvero
    // archi al grafo di routing: lines non vuoto e dash assente (i layer non
    // ciclabili tipo ferrovie/confini - vedi NON_CICLABILE_LAYERS in
    // build_routing_graph.py - hanno lines non vuoto ma dash valorizzato, e
    // i layer solo-punti come le rastrelliere hanno lines vuoto). Bug
    // segnalato da Alessandro il 2026-10-02: prima comparivano qui anche le
    // rastrelliere (mai instradabili) mentre Passaggi segreti, che invece lo
    // e', non risultava "spuntato" - due cose diverse (visibilita' sulla
    // mappa vs. partecipazione al routing) che qui vanno a coincidere.
    val detailLayersBySource = remember(detailLayers) {
        detailLayers.filter { it.lines.isNotEmpty() && it.dash == null }.groupBy { it.source }
    }
    var enabledDetailLayers by remember {
        mutableStateOf(SettingsRepository.getEnabledDetailLayers(context, detailLayers.map { it.key }))
    }
    // Solo stato di presentazione (quali sorgenti sono "aperte a ventaglio"
    // in questo momento) - non persistito apposta, si riparte sempre chiuso
    // riaprendo Impostazioni, nessun bisogno di ricordarlo tra una sessione
    // e l'altra.
    var expandedSources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mapStyle by remember { mutableStateOf(SettingsRepository.getMapStyle(context)) }

    fun updateRuleParams(update: (RuleParams) -> RuleParams) {
        val updated = update(ruleParams)
        ruleParams = updated
        SettingsRepository.setRuleParams(context, updated)
    }

    // Nessuna freccia "indietro": e' una delle 4 voci della barra in
    // basso (icona ingranaggio), non piu' una sotto-schermata raggiunta da
    // Home - si lascia semplicemente toccando un'altra icona della barra.
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Impostazioni") })
        },
    ) { padding: PaddingValues ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            item {
                Text(
                    text = "Sorgenti usate per calcolare i percorsi",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            itemsIndexed(sources) { index, source ->
                if (index == separatorIndex) {
                    Text(
                        text = "do not use",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Divider()
                }
                val layersForSource = detailLayersBySource[source.key] ?: emptyList()
                val isExpanded = source.key in expandedSources
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = source.key in enabled,
                        onCheckedChange = { checked ->
                            val updated = if (checked) enabled + source.key else enabled - source.key
                            enabled = updated
                            SettingsRepository.setEnabledSources(context, updated)
                        },
                    )
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(colors.getValue(source.key)))
                            .clickable { colorPickerForSource = source.key },
                    )
                    // La freccina (e il tocco per aprire/chiudere) compare
                    // solo se questa sorgente ha davvero dei layer di
                    // dettaglio - nessun "a ventaglio" vuoto da aprire per
                    // una sorgente che oggi non ne porta nessuno. Testo a
                    // parte con titleLarge apposta: dentro la stessa Text
                    // dell'etichetta risultava illeggibile ("non si vede
                    // nulla", Alessandro 2026-10-02).
                    val toggleModifier = if (layersForSource.isEmpty()) {
                        Modifier.padding(start = 8.dp)
                    } else {
                        Modifier
                            .padding(start = 8.dp)
                            .clickable {
                                expandedSources = if (isExpanded) {
                                    expandedSources - source.key
                                } else {
                                    expandedSources + source.key
                                }
                            }
                    }
                    Row(modifier = toggleModifier, verticalAlignment = Alignment.CenterVertically) {
                        if (layersForSource.isNotEmpty()) {
                            Text(
                                text = if (isExpanded) "▾" else "▸",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        Text(text = "${source.label} (${source.edgeCount} tratti)")
                    }
                }
                if (isExpanded) {
                    layersForSource.forEach { layer ->
                        val layerChecked = layer.key in enabledDetailLayers
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    enabledDetailLayers = if (layerChecked) {
                                        enabledDetailLayers - layer.key
                                    } else {
                                        enabledDetailLayers + layer.key
                                    }
                                    SettingsRepository.setEnabledDetailLayers(context, enabledDetailLayers)
                                }
                                .padding(start = 48.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = layerChecked, onCheckedChange = null)
                            Text(
                                text = "${DetailLayerDisplay.symbolFor(layer.key)}  ${layer.label}",
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                Divider()
            }
            if (sources.isEmpty()) {
                item {
                    Text(
                        text = "Nessuna sorgente disponibile: esegui precompila per generare i dati.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item {
                Text(
                    text = "Motore di calcolo percorso",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(
                listOf(
                    RoutingEngine.IN_HOUSE to "In-house (Dijkstra originale)",
                    RoutingEngine.JGRAPHT to "JGraphT (libreria open-source)",
                ),
            ) { _, (candidate, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = engine == candidate,
                        onClick = {
                            engine = candidate
                            SettingsRepository.setRoutingEngine(context, candidate)
                        },
                    )
                    Text(text = label)
                }
            }
            item {
                Text(
                    text = "Percorsi alternativi",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Percorsi per ciascuna delle 3 regole (totale fino a ${routesPerRule * 3})",
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    IconButton(onClick = {
                        val updated = (routesPerRule - 1).coerceIn(1, 4)
                        routesPerRule = updated
                        SettingsRepository.setRoutesPerRule(context, updated)
                    }) { Text("-") }
                    Text(text = "$routesPerRule")
                    IconButton(onClick = {
                        val updated = (routesPerRule + 1).coerceIn(1, 4)
                        routesPerRule = updated
                        SettingsRepository.setRoutesPerRule(context, updated)
                    }) { Text("+") }
                }
            }
            item {
                Text(
                    text = "Parametri delle regole di preferenza",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            item {
                RuleParamField(
                    label = "Budget: margine massimo sul percorso piu' breve (%)",
                    value = ruleParams.budgetCapPercent,
                    onValueChange = { v -> updateRuleParams { it.copy(budgetCapPercent = v) } },
                )
            }
            item {
                RuleParamField(
                    label = "Budget: peso delle sorgenti non preferite",
                    value = ruleParams.budgetSicWeight,
                    onValueChange = { v -> updateRuleParams { it.copy(budgetSicWeight = v) } },
                )
            }
            item {
                RuleParamField(
                    label = "Supplemento per sorgente non preferita (m per arco)",
                    value = ruleParams.sourceSurchargeM,
                    onValueChange = { v -> updateRuleParams { it.copy(sourceSurchargeM = v) } },
                )
            }
            item {
                RuleParamField(
                    label = "Direzionale: arretramento massimo rispetto alla meta (%)",
                    value = ruleParams.directionalBackwardPercent,
                    onValueChange = { v -> updateRuleParams { it.copy(directionalBackwardPercent = v) } },
                )
            }
            item {
                RuleParamField(
                    label = "Direzionale: peso delle sorgenti non preferite",
                    value = ruleParams.directionalSicWeight,
                    onValueChange = { v -> updateRuleParams { it.copy(directionalSicWeight = v) } },
                )
            }
            item {
                Text(
                    text = "Dati mappa",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            item {
                // I dati (grafo/indirizzi) non sono piu' dentro l'app: si
                // scaricano da un server al primo avvio, e questo toggle
                // decide solo se un aggiornamento trovato in seguito va
                // scaricato subito o chiesto prima - in entrambi i casi
                // serve poi riavviare l'app per vederlo (vedi
                // BackgroundUpdateCheck in MainActivity.kt).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Aggiorna automaticamente i dati mappa quando disponibili",
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(
                        checked = autoUpdateMapData,
                        onCheckedChange = { checked ->
                            autoUpdateMapData = checked
                            SettingsRepository.setAutoUpdateMapData(context, checked)
                        },
                    )
                }
            }
            item {
                Text(
                    text = "Mappa di base",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(
                listOf(
                    MapStyle.MAPNIK to "OpenStreetMap classico (con ospedali/farmacie/parcheggi)",
                    MapStyle.CYCLOSM to "CyclOSM (stile per ciclisti, senza queste icone)",
                ),
            ) { _, (candidate, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mapStyle == candidate,
                        onClick = {
                            mapStyle = candidate
                            SettingsRepository.setMapStyle(context, candidate)
                        },
                    )
                    Text(text = label)
                }
            }
        }
    }

    colorPickerForSource?.let { sourceKey ->
        AlertDialog(
            onDismissRequest = { colorPickerForSource = null },
            title = { Text("Colore sorgente") },
            text = {
                Column {
                    COLOR_PALETTE.forEach { (name, argb) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    colors = colors + (sourceKey to argb)
                                    SettingsRepository.setSourceColor(context, sourceKey, argb)
                                    colorPickerForSource = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb)),
                            )
                            Text(text = "  $name")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { colorPickerForSource = null }) { Text("Chiudi") }
            },
        )
    }
}

/** Un campo numerico libero per uno dei parametri di RuleParams - input
 * invalido o vuoto lascia semplicemente il valore precedente invariato
 * (niente crash, niente valore a caso): abbastanza per numeri che comunque
 * l'utente puo' sempre rimettere a posto. */
@Composable
private fun RuleParamField(label: String, value: Double, onValueChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(formatRuleParam(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private fun formatRuleParam(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
