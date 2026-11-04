package it.salvaiciclisti.app.routing

import it.salvaiciclisti.app.json.SimpleJson
import it.salvaiciclisti.app.json.arr
import it.salvaiciclisti.app.json.num
import it.salvaiciclisti.app.json.obj
import it.salvaiciclisti.app.json.str
import it.salvaiciclisti.app.json.strOrNull

data class DetailPoint(val point: LatLon, val name: String?)
data class DetailLine(val geom: List<LatLon>, val name: String?)

/**
 * Un layer "di dettaglio" (punti di interesse e/o tracciati informativi,
 * es. bike box, rastrelliere, punti critici, passaggi segreti, ferrovie,
 * confini...) mostrato a scelta dall'utente sulla mappa (pulsante "layers"
 * in MapScreen.kt) - vedi build_detail_layers() in
 * scripts/build_routing_graph.py per come viene costruito layers.json.
 * [source] e' la stessa chiave di SOURCE_LABELS (es. "salvaiciclisti_sicrm"):
 * un layer di dettaglio appartiene sempre a UNA sola sorgente raccolta,
 * usata sia per condividerne il colore in Impostazioni sia per decidere se
 * e' selezionabile nel pannello "layers" (solo se quella sorgente e'
 * abilitata al routing, vedi MapScreen.kt - 2026-10-02, Alessandro: "se non
 * e' selezionata la sorgente primaria nei percorsi non c'e' proprio la
 * possibilita' di selezionare i treni").
 *
 * ATTENZIONE: questo file e' costruito a parte da graph.json, ma questo non
 * vuol dire che i suoi dati non siano MAI instradabili - Passaggi_Segreti
 * lo e' (entra anche in graph.json via load_lines), le altre 8 di SIC no
 * (solo punti, mai instradabili). [dash] distingue visivamente i layer che
 * NON sono piste ciclabili vere (es. ferrovie, confini amministrativi -
 * vedi NON_CICLABILE_LAYERS in build_routing_graph.py, esclusi li' dal
 * grafo apposta): null/assente per tratto continuo (percorso vero o
 * punto), un pattern di tratteggio altrimenti - mai "solid" per questi
 * ultimi, cosi' non si confondono con un percorso vero della stessa
 * sorgente/colore. Vedi le note di progetto per il perche' di tutto questo.
 */
data class DetailLayer(
    val key: String,
    val label: String,
    val source: String,
    val points: List<DetailPoint>,
    val lines: List<DetailLine>,
    val dash: String? = null,
)

object DetailLayersLoader {
    fun parse(jsonText: String): List<DetailLayer> {
        val root = SimpleJson.parse(jsonText).obj()
        return root.getValue("layers").arr().map { layerValue ->
            val o = layerValue.obj()
            val points = (o["points"]?.arr() ?: emptyList()).map { p ->
                val po = p.obj()
                DetailPoint(
                    point = LatLon(po.getValue("lat").num(), po.getValue("lon").num()),
                    name = po["name"]?.strOrNull(),
                )
            }
            val lines = (o["lines"]?.arr() ?: emptyList()).map { l ->
                val lo = l.obj()
                val geom = lo.getValue("geom").arr().map { pair ->
                    val coords = pair.arr()
                    LatLon(coords[0].num(), coords[1].num())
                }
                DetailLine(geom = geom, name = lo["name"]?.strOrNull())
            }
            DetailLayer(
                key = o.getValue("key").str(),
                label = o.getValue("label").str(),
                source = o.getValue("source").str(),
                points = points,
                lines = lines,
                dash = o["dash"]?.strOrNull(),
            )
        }
    }
}
