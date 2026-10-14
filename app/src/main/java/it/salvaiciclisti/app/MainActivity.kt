package it.salvaiciclisti.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import it.salvaiciclisti.app.data.AssetRepository
import it.salvaiciclisti.app.data.RemoteMapDataRepository
import it.salvaiciclisti.app.data.SettingsRepository
import it.salvaiciclisti.app.map.MapScreen
import it.salvaiciclisti.app.map.MapSearchState
import it.salvaiciclisti.app.map.NavigateScreen
import it.salvaiciclisti.app.routing.RouteResult
import it.salvaiciclisti.app.ui.ProfiloScreen
import it.salvaiciclisti.app.ui.RouteDetailScreen
import it.salvaiciclisti.app.ui.SettingsScreen
import it.salvaiciclisti.app.ui.SicRomaScreen
import it.salvaiciclisti.app.ui.theme.SalvaiciclistiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SalvaiciclistiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SalvaiciclistiApp()
                }
            }
        }
    }
}

/**
 * Le 4 sezioni raggiungibili dalla barra in basso, esattamente come
 * specificato da Alessandro il 2026-09-18 ("non voglio i bottoni nella
 * home... 4 icone con testo"): play-in-cerchio/"naviga" (la mappa/ricerca
 * percorso, che prima si apriva dal bottone "Mappa"), ingranaggio/
 * "settings", bicicletta/"SiC-Roma" e mezzobusto/"profilo". Le ultime due
 * non hanno ancora un contenuto definito - vedi SicRomaScreen/ProfiloScreen.
 *
 * Tutte e 4 le icone sono vector drawable locali (res/drawable/ic_*.xml),
 * NON androidx.compose.material.icons: quel pacchetto (Icons.Filled.X) non
 * e' incluso da material3 - serve una dipendenza a parte
 * (material-icons-core/extended) che il progetto ha sempre evitato di
 * aggiungere solo per un paio di icone (stesso motivo per cui non c'e'
 * navigation-compose), ed e' comunque la via che Google sconsiglia ora
 * (raccomandano di scaricare l'XML da fonts.google.com/icons). ic_naviga.xml
 * e ic_bici_sic.xml sono gia' definitive (quest'ultima riusa esattamente il
 * disegno della bici del logo); ic_settings.xml e ic_profilo.xml sono
 * SEGNAPOSTO - Alessandro mette lui l'icona definitiva in quei due file,
 * stesso nome, senza dover toccare questo Kotlin.
 */
private enum class BottomTab(val label: String) {
    NAVIGA("naviga"),
    SETTINGS("settings"),
    SIC_ROMA("SiC-Roma"),
    PROFILO("profilo"),
}

/**
 * A handful of mutable "what's open" values instead of a NavHost - same
 * spirit as GTL's own MainActivity (see its doc comment: "no more machinery
 * than the app actually needs"). [Screen.Tabs] e' il livello principale (le
 * 4 sezioni della barra in basso, vedi [BottomTab]); [Screen.RouteDetail] e
 * [Screen.Navigate] sono le uniche due sotto-schermate che devono ricordare
 * un [RouteResult] attraverso un detour, quindi restano fuori dalla barra
 * (schermo intero, senza tab visibili) esattamente come prima.
 */
private sealed class Screen {
    data object Tabs : Screen()
    data class RouteDetail(val route: RouteResult) : Screen()
    data class Navigate(val route: RouteResult) : Screen()
}

@Composable
fun SalvaiciclistiApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Tabs) }
    var currentTab by remember { mutableStateOf(BottomTab.NAVIGA) }
    // Owned here, not inside MapScreen - see MapSearchState's own doc: this
    // e' cio' che fa si' che "Indietro" dalla schermata di dettaglio
    // percorso torni alla mappa con i percorsi appena trovati ancora
    // presenti, invece che vuota.
    val mapSearchState = remember { MapSearchState() }

    when (val current = screen) {
        is Screen.Tabs -> {
            // Nessun BackHandler qui apposta: il back di sistema dal
            // livello principale (qualunque tab) chiude l'app, il
            // comportamento standard Android per una bottom navigation -
            // non c'e' piu' una Home "sopra" le tab a cui tornare.
            //
            // MapDataGate avvolge le tab (non RouteDetail/Navigate, che si
            // raggiungono solo DOPO aver gia' trovato un percorso, quindi
            // con i dati gia' presenti): al primissimo avvio non c'e' ancora
            // nessun dato mappa locale (Alessandro, 2026-09-21: niente piu'
            // graph.json/sources_manifest.json/indirizzi.json dentro l'APK,
            // li scarica l'app da GitHub Releases), quindi va scaricato
            // prima di poter mostrare qualunque tab.
            MapDataGate {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            BottomTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        val iconRes = when (tab) {
                                            BottomTab.NAVIGA -> R.drawable.ic_naviga
                                            BottomTab.SETTINGS -> R.drawable.ic_settings
                                            BottomTab.SIC_ROMA -> R.drawable.ic_bici_sic
                                            BottomTab.PROFILO -> R.drawable.ic_profilo
                                        }
                                        Icon(painterResource(iconRes), contentDescription = tab.label)
                                    },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        when (currentTab) {
                            BottomTab.NAVIGA -> MapScreen(
                                state = mapSearchState,
                                onRouteSelected = { route -> screen = Screen.RouteDetail(route) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            BottomTab.SETTINGS -> SettingsScreen()
                            BottomTab.SIC_ROMA -> SicRomaScreen()
                            BottomTab.PROFILO -> ProfiloScreen()
                        }
                    }
                }
            }
        }
        is Screen.RouteDetail -> {
            BackHandler { screen = Screen.Tabs }
            RouteDetailScreen(
                route = current.route,
                onBack = { screen = Screen.Tabs },
                onNavigate = { route -> screen = Screen.Navigate(route) },
            )
        }
        is Screen.Navigate -> {
            // No BackHandler falling through to the map here on purpose:
            // leaving "Naviga" via the system back gesture should stop
            // tracking exactly like the "Termina navigazione" button does,
            // not silently keep the foreground service running underneath
            // the route detail screen.
            BackHandler { screen = Screen.RouteDetail(current.route) }
            NavigateScreen(
                route = current.route,
                onExit = { screen = Screen.RouteDetail(current.route) },
            )
        }
    }
}

/**
 * "Cancello" davanti a [content] (le 4 tab principali): al primissimo avvio
 * (nessun dato mappa locale) mostra una schermata di download bloccante
 * invece della mappa vuota. Una volta che i dati locali ci sono (appena
 * scaricati o di un'installazione precedente), mostra [content] e in
 * parallelo controlla in background se il server ne ha una versione piu'
 * recente (vedi [BackgroundUpdateCheck]) - senza mai bloccare l'uso della
 * app nel frattempo.
 */
@Composable
private fun MapDataGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasLocalData by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        hasLocalData = withContext(Dispatchers.IO) { AssetRepository.hasLocalData(context) }
    }

    when (hasLocalData) {
        null -> CenteredMessage("Verifica dati mappa...")
        false -> FirstDownloadScreen(onInstalled = { hasLocalData = true })
        true -> {
            content()
            BackgroundUpdateCheck()
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

/**
 * Schermata a tutto schermo mostrata SOLO quando non esiste ancora nessun
 * dato locale (prima apertura dopo l'installazione, dato che l'app non li
 * porta piu' con se'): scarica graph.json/sources_manifest.json/
 * indirizzi.json da GitHub Releases, e in caso di errore (tipicamente
 * nessuna connessione) mostra un messaggio con un pulsante "Riprova" invece
 * di lasciare l'app bloccata su una schermata di solo caricamento.
 */
@Composable
private fun FirstDownloadScreen(onInstalled: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        val manifest = RemoteMapDataRepository.fetchRemoteManifest()
        if (manifest == null) {
            error = "Impossibile contattare il server. Controlla la connessione e riprova."
            return@LaunchedEffect
        }
        val result = RemoteMapDataRepository.downloadAndInstall(context, manifest.version)
        if (result.isSuccess) {
            onInstalled()
        } else {
            error = "Scaricamento fallito: ${result.exceptionOrNull()?.message ?: "errore sconosciuto"}"
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val currentError = error
            if (currentError == null) {
                Text("Primo avvio: scaricamento dati mappa in corso...")
            } else {
                Text(currentError)
                TextButton(onClick = { attempt += 1 }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Riprova")
                }
            }
        }
    }
}

/** I possibili esiti del controllo aggiornamenti in background - vedi
 * [BackgroundUpdateCheck]. Un fallimento di rete durante il controllo NON e'
 * un caso a parte: viene trattato come [UpToDate] (si continua in silenzio
 * con i dati che ci sono, si riprovera' al prossimo avvio). */
private sealed class UpdateState {
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val version: String) : UpdateState()
    data class Downloading(val version: String) : UpdateState()
    data object Installed : UpdateState()
    data object Failed : UpdateState()
}

/**
 * Non disegna nulla di suo a parte gli eventuali dialoghi: controlla una
 * volta per apertura della app se il server ha una versione dei dati mappa
 * piu' recente di quella installata, e secondo il toggle "aggiornamento
 * automatico" (Impostazioni) la scarica subito o chiede conferma. In
 * entrambi i casi l'aggiornamento scaricato richiede un riavvio della app
 * per essere usato: il grafo gia' in memoria (vedi AssetRepository) non
 * viene sostituito a caldo mentre e' in uso, per non rischiare una route in
 * corso su un mix di dati vecchi/nuovi.
 */
@Composable
private fun BackgroundUpdateCheck() {
    val context = LocalContext.current
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

    LaunchedEffect(Unit) {
        val remote = RemoteMapDataRepository.fetchRemoteManifest()
        val local = RemoteMapDataRepository.getLocalVersion(context)
        state = when {
            remote == null -> UpdateState.UpToDate
            remote.version == local -> UpdateState.UpToDate
            SettingsRepository.getAutoUpdateMapData(context) -> UpdateState.Downloading(remote.version)
            else -> UpdateState.Available(remote.version)
        }
    }

    LaunchedEffect(state) {
        val current = state
        if (current is UpdateState.Downloading) {
            val result = RemoteMapDataRepository.downloadAndInstall(context, current.version)
            state = if (result.isSuccess) UpdateState.Installed else UpdateState.Failed
        }
    }

    when (val current = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { state = UpdateState.UpToDate },
            title = { Text("Aggiornamento disponibile") },
            text = {
                Text(
                    "È disponibile una versione più recente dei dati mappa. " +
                        "Scaricarla ora? Servirà riavviare l'app per usarla.",
                )
            },
            confirmButton = {
                TextButton(onClick = { state = UpdateState.Downloading(current.version) }) {
                    Text("Aggiorna")
                }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateState.UpToDate }) { Text("Non ora") }
            },
        )
        is UpdateState.Installed -> AlertDialog(
            onDismissRequest = { state = UpdateState.UpToDate },
            title = { Text("Dati aggiornati") },
            text = { Text("Riavvia l'app per usare i nuovi dati mappa.") },
            confirmButton = {
                TextButton(onClick = { state = UpdateState.UpToDate }) { Text("Ok") }
            },
        )
        else -> Unit
    }
}
