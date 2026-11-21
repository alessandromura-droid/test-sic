package it.salvaiciclisti.app.data

import android.content.Context
import android.util.Log
import it.salvaiciclisti.app.routing.AddressIndex
import it.salvaiciclisti.app.routing.DetailLayer
import it.salvaiciclisti.app.routing.DetailLayersLoader
import it.salvaiciclisti.app.routing.GraphLoader
import it.salvaiciclisti.app.routing.RoutingGraph
import it.salvaiciclisti.app.routing.SourceInfo
import it.salvaiciclisti.app.routing.SourcesManifestLoader
import java.io.File

private const val PERF_TAG = "SiCPerf"

/**
 * Legge graph.json/sources_manifest.json/indirizzi.json e li tiene in
 * memoria per il resto della vita del processo - non cambiano finche' non
 * arriva un aggiornamento scaricato da GitHub Releases (vedi [invalidate]).
 *
 * (Alessandro, 2026-09-21: questi file non sono piu' asset impacchettati
 * nell'APK - li scarica [RemoteMapDataRepository] dentro lo storage privato
 * dell'app, la prima volta che parte, prima che qualunque tab sia visibile
 * (vedi il "cancello" MapDataGate in MainActivity.kt). Qui si legge solo da
 * li', assumendo che a questo punto i file esistano gia' - [hasLocalData]
 * e' quello che MapDataGate controlla per saperlo.)
 *
 * (2026-09-19: Log.d(PERF_TAG, ...) temporanei sotto e in MapScreen.kt, per
 * misurare da logcat dove va il tempo all'avvio/al ritorno sulla mappa - da
 * togliere una volta capito.)
 */
object AssetRepository {
    // (2026-11-20) Un grafo e un manifest per rete (vedi [ReteSupporto]):
    // chiave = nome del file letto.
    private val graphs = HashMap<String, RoutingGraph>()
    private val sourcesByFile = HashMap<String, List<SourceInfo>>()
    @Volatile private var addressIndex: AddressIndex? = null
    @Volatile private var detailLayers: List<DetailLayer>? = null

    private const val GRAPH_HEATMAP = "graph_heatmap.json"
    private const val MANIFEST_HEATMAP = "sources_manifest_heatmap.json"

    /** Vero se il grafo heat map (graph_heatmap.json + il suo manifest) e'
     * installato. I/O su disco minimo (due File.exists). */
    fun hasHeatmapData(context: Context): Boolean {
        val dir = routingDir(context)
        return File(dir, GRAPH_HEATMAP).exists() && File(dir, MANIFEST_HEATMAP).exists()
    }

    /** Rete effettivamente usata: HEATMAP solo se richiesta e installata,
     * altrimenti RACCORDI. */
    fun reteEffettiva(context: Context, rete: ReteSupporto): ReteSupporto =
        if (rete == ReteSupporto.HEATMAP && hasHeatmapData(context)) ReteSupporto.HEATMAP else ReteSupporto.RACCORDI

    /** (2026-11-21) Reti effettivamente usabili fra quelle richieste, in
     * ordine fisso (RACCORDI, HEATMAP), senza doppioni: la heat map non
     * installata diventa RACCORDI. Mai vuota. */
    fun retiEffettive(context: Context, reti: Set<ReteSupporto>): List<ReteSupporto> =
        ReteSupporto.values().filter { it in reti }.map { reteEffettiva(context, it) }.distinct()
            .ifEmpty { listOf(ReteSupporto.RACCORDI) }

    private fun graphFile(context: Context, rete: ReteSupporto): String =
        if (reteEffettiva(context, rete) == ReteSupporto.HEATMAP) GRAPH_HEATMAP else "graph.json"

    private fun manifestFile(context: Context, rete: ReteSupporto): String =
        if (reteEffettiva(context, rete) == ReteSupporto.HEATMAP) MANIFEST_HEATMAP else "sources_manifest.json"

    fun getGraph(context: Context, rete: ReteSupporto = ReteSupporto.RACCORDI): RoutingGraph {
        val name = graphFile(context, rete)
        return synchronized(this) {
            // (2026-11-21) Entrambi i grafi possono stare in memoria insieme
            // (l'app calcola su tutte e due le reti); prima se ne teneva uno.
            graphs.getOrPut(name) {
                val tRead0 = System.currentTimeMillis()
                val text = readLocalFile(context, name)
                val tRead1 = System.currentTimeMillis()
                val g = GraphLoader.parse(text)
                val tParse1 = System.currentTimeMillis()
                Log.d(
                    PERF_TAG,
                    "getGraph($name): lettura file ${tRead1 - tRead0} ms, parse ${tParse1 - tRead1} ms - " +
                        "${g.nodes.size} nodi, ${g.edges.size} archi",
                )
                g
            }
        }
    }

    fun getSources(context: Context, rete: ReteSupporto = ReteSupporto.RACCORDI): List<SourceInfo> {
        val name = manifestFile(context, rete)
        return synchronized(this) {
            sourcesByFile.getOrPut(name) {
                val t0 = System.currentTimeMillis()
                val list = SourcesManifestLoader.parse(readLocalFile(context, name))
                Log.d(PERF_TAG, "getSources($name): ${System.currentTimeMillis() - t0} ms, ${list.size} sorgenti")
                list
            }
        }
    }

    /** Null se indirizzi.json non e' disponibile (es. l'ultimo precompila.py
     * ha fallito la sua fetch Overpass per l'indice indirizzi, mentre il
     * grafo di instradamento - che non serve rete in fase di build - e'
     * comunque buono). I chiamanti trattano questo come "ricerca indirizzi
     * non disponibile", mai come un crash. */
    fun getAddressIndex(context: Context): AddressIndex? =
        addressIndex ?: synchronized(this) {
            addressIndex ?: runCatching {
                val t0 = System.currentTimeMillis()
                AddressIndex.parse(readLocalFile(context, "indirizzi.json")).also {
                    Log.d(PERF_TAG, "getAddressIndex: ${System.currentTimeMillis() - t0} ms")
                }
            }.getOrNull()?.also { addressIndex = it }
        }

    /** Layer di dettaglio (bike box, rastrelliere, punti critici, passaggi
     * segreti... - vedi [it.salvaiciclisti.app.routing.DetailLayer]) mostrati
     * a scelta dal pulsante "layers" di MapScreen - lista vuota (mai un
     * crash) se layers.json non e' ancora disponibile: sia perche' Alessandro
     * non ha ancora ripubblicato dopo questa modifica (2026-10-02), sia
     * perche' questo asset e' facoltativo (vedi
     * RemoteMapDataRepository.OPTIONAL_ASSETS) e puo' mancare anche su
     * un'installazione gia' aggiornata. */
    fun getDetailLayers(context: Context): List<DetailLayer> =
        detailLayers ?: synchronized(this) {
            detailLayers ?: runCatching {
                DetailLayersLoader.parse(readLocalFile(context, "layers.json"))
            }.getOrDefault(emptyList()).also { detailLayers = it }
        }

    /** Vero se graph.json e sources_manifest.json sono gia' stati scaricati
     * (indirizzi.json e' facoltativo, vedi [getAddressIndex]) - e' quello
     * che MapDataGate controlla prima di mostrare qualunque tab. Fa I/O su
     * disco: va chiamata da un thread di background (vedi Dispatchers.IO in
     * MapDataGate), mai dal thread principale. */
    fun hasLocalData(context: Context): Boolean {
        val dir = routingDir(context)
        return File(dir, "graph.json").exists() && File(dir, "sources_manifest.json").exists()
    }

    /** Scarta la cache in memoria dopo che [RemoteMapDataRepository] ha
     * scaricato una nuova versione, cosi' la prossima getGraph()/
     * getSources()/getAddressIndex() rilegge i file appena scritti invece
     * del grafo vecchio tenuto in RAM. Da sola non basta a far vedere i dati
     * nuovi in schermate gia' composte (RoutingGraph/FilteredGraph a loro
     * volta tenuti in remember{} dentro MapScreen) - per questo dopo un
     * aggiornamento si chiede di riavviare l'app invece di provare a
     * "scambiare" il grafo a caldo mentre e' in uso. */
    fun invalidate() {
        synchronized(this) {
            graphs.clear()
            sourcesByFile.clear()
            addressIndex = null
            detailLayers = null
        }
    }

    private fun routingDir(context: Context): File = File(context.filesDir, "routing")

    private fun readLocalFile(context: Context, name: String): String =
        File(routingDir(context), name).readText(Charsets.UTF_8)
}
