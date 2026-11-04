package it.salvaiciclisti.app.routing

import java.util.PriorityQueue
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DirectedWeightedMultigraph

/**
 * Parametri di calcolo della nuova pagina Impostazioni (Alessandro,
 * 2026-10-14): le sorgenti sono fisse (tutte quelle del grafo pubblicato,
 * cioe' SIC + raccordi), l'utente sceglie solo come pesarle.
 *
 * Costo di un tratto = [pesoKm] x lunghezza x (peso della sua sorgente) +
 * [pesoSalita] x metri di salita nel verso di percorrenza. [pesoSalita] e'
 * quindi "quanti metri di strada piana vale un metro di salita".
 *
 * Due valvole di sicurezza, come nelle regole precedenti (RoutingRules.kt):
 * un percorso viene scartato se e' piu' lungo del piu' breve assoluto oltre
 * [margineMassimoPercent], o se arretra rispetto all'asse partenza->arrivo
 * oltre [arretramentoMassimoPercent] della distanza in linea d'aria.
 *
 * (2026-11-01) Sette categorie di tratti, ciascuna col suo peso (vedi
 * [sourceWeightOf]): SIC, raccordi, Ciclopolitana mancante, percorsi nel
 * verde, strade scolastiche, cantieri, piste rimosse. [evitaParchi] toglie
 * del tutto i percorsi nel verde (parchi chiusi la notte): lo applica chi
 * costruisce il [FilteredGraph] (MapScreen), vedi [dipendeDalVerde].
 */
data class AppRoutingParams(
    val engine: RoutingEngine = RoutingEngine.IN_HOUSE,
    val numeroPercorsi: Int = 3,
    val pesoSic: Double = 1.0,
    val pesoRaccordi: Double = 3.0,
    val pesoCiclopolitana: Double = 2.0,
    val pesoVerde: Double = 1.5,
    val pesoScolastiche: Double = 1.5,
    val pesoCantieri: Double = 3.0,
    val pesoRimosse: Double = 3.0,
    val evitaParchi: Boolean = false,
    val pesoKm: Double = 1.0,
    val pesoSalita: Double = 10.0,
    val margineMassimoPercent: Double = 30.0,
    val arretramentoMassimoPercent: Double = 10.0,
    val sicKey: String = "salvaiciclisti_sicrm",
)

const val VERDE_SOURCE = "salvaiciclisti_sicrm_verde"
const val CANTIERI_SOURCE = "salvaiciclisti_sicrm_cantieri"
const val RIMOSSE_SOURCE = "salvaiciclisti_sicrm_rimosse"
const val CICLOPOLITANA_MANCANTE_SOURCE = "ciclopolitana_mancante"

/** (2026-11-02) Percorsi nel verde di SIC e raccordi "_verde" (collegano i
 * percorsi nel verde): tutti tolti da "Evita parchi". */
fun dipendeDalVerde(source: String): Boolean = source.contains("_verde")

/** Peso (moltiplica la lunghezza) della categoria della sorgente [source]. */
fun sourceWeightOf(source: String, p: AppRoutingParams): Double = when {
    source == VERDE_SOURCE -> p.pesoVerde
    source == CANTIERI_SOURCE -> p.pesoCantieri
    source == RIMOSSE_SOURCE -> p.pesoRimosse
    source == p.sicKey -> p.pesoSic
    source == CICLOPOLITANA_MANCANTE_SOURCE -> p.pesoCiclopolitana
    source.startsWith("raccordi_scuola") -> p.pesoScolastiche
    else -> p.pesoRaccordi
}

/** Salita (m) lungo una lista di quote, nel verso della lista. */
internal fun climbOf(ele: List<Double>?): Double {
    if (ele == null || ele.size < 2) return 0.0
    var up = 0.0
    for (k in 1 until ele.size) {
        val d = ele[k] - ele[k - 1]
        if (d > 0) up += d
    }
    return up
}

/** Salita e discesa totali (m) di un percorso, dalle quote dei suoi tratti.
 * (0, 0) se il grafo non ha quote. */
fun routeAscentDescent(route: RouteResult): Pair<Double, Double> {
    var up = 0.0
    var down = 0.0
    for (step in route.steps) {
        val ele = step.ele ?: continue
        for (k in 1 until ele.size) {
            val d = ele[k] - ele[k - 1]
            if (d > 0) up += d else down -= d
        }
    }
    return up to down
}

/** Profilo altimetrico: (distanza progressiva in m, quota in m) per ogni
 * punto del percorso con quota. Vuoto se il grafo non ha quote. */
fun routeElevationProfile(route: RouteResult): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var dist = 0.0
    var prev: LatLon? = null
    for (step in route.steps) {
        val ele = step.ele ?: continue
        step.geom.forEachIndexed { k, p ->
            prev?.let { dist += haversineMeters(it, p) }
            prev = p
            if (k < ele.size) out.add(dist to ele[k])
        }
    }
    return out
}

/** Metri dei tratti di un percorso, divisi fra la famiglia SIC (sorgenti che
 * iniziano con [sicKey]: esistenti, verde, cantieri, rimosse) e tutte le altre
 * (raccordi, Ciclopolitana mancante). */
fun routeSicOtherLengths(route: RouteResult, sicKey: String): Pair<Double, Double> {
    var sic = 0.0
    var other = 0.0
    for (step in route.steps) if (step.source.startsWith(sicKey)) sic += step.lengthM else other += step.lengthM
    return sic to other
}

/** Ricerca del cammino di costo minimo con un costo che dipende dal VERSO di
 * percorrenza (la salita), per entrambi i motori. [cost] riceve (id arco,
 * nodo di partenza, nodo di arrivo). */
private fun solveWeighted(
    filtered: FilteredGraph,
    query: RoutingQuery,
    bannedEdges: Set<Int>,
    engine: RoutingEngine,
    cost: (Int, Int, Int) -> Double,
): List<TraversedEdge>? = when (engine) {
    RoutingEngine.IN_HOUSE -> {
        val dist = HashMap<Int, Double>()
        val prevNode = HashMap<Int, Int>()
        val prevEdge = HashMap<Int, Int>()
        dist[query.startNode] = 0.0
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        pq.add(0.0 to query.startNode)
        val visited = HashSet<Int>()
        while (pq.isNotEmpty()) {
            val (d, node) = pq.poll()
            if (!visited.add(node)) continue
            if (node == query.endNode) break
            for ((edgeId, other) in neighborsOf(filtered, query, node)) {
                if (edgeId >= 0 && edgeId in bannedEdges) continue
                val nd = d + cost(edgeId, node, other)
                if (nd < (dist[other] ?: Double.MAX_VALUE)) {
                    dist[other] = nd
                    prevNode[other] = node
                    prevEdge[other] = edgeId
                    pq.add(nd to other)
                }
            }
        }
        if (query.endNode !in dist) {
            null
        } else {
            val traversed = mutableListOf<TraversedEdge>()
            var cur = query.endNode
            while (cur != query.startNode) {
                val edgeId = prevEdge.getValue(cur)
                val from = prevNode.getValue(cur)
                traversed.add(TraversedEdge(edgeId, from, cur))
                cur = from
            }
            traversed.reverse()
            traversed
        }
    }
    RoutingEngine.JGRAPHT -> {
        // Grafo ORIENTATO: ogni arco diventa due archi (uno per verso), cosi'
        // la salita puo' pesare diversamente in andata e in ritorno.
        data class QArc(val edgeId: Int, val from: Int, val to: Int)
        val g = DirectedWeightedMultigraph<Int, QArc>(QArc::class.java)
        for (n in 0 until query.totalNodes) g.addVertex(n)
        fun addArcs(n: Int, list: List<Pair<Int, Int>>) {
            for ((edgeId, other) in list) {
                if (other == n) continue  // anello: inutile e rifiutato
                if (edgeId >= 0 && edgeId in bannedEdges) continue
                val arc = QArc(edgeId, n, other)
                if (g.addEdge(n, other, arc)) g.setEdgeWeight(arc, cost(edgeId, n, other))
            }
        }
        for (n in 0 until query.nodeCount) addArcs(n, filtered.adjacency[n])
        for ((n, list) in query.extra) addArcs(n, list)
        DijkstraShortestPath(g).getPath(query.startNode, query.endNode)
            ?.edgeList?.map { TraversedEdge(it.edgeId, it.from, it.to) }
    }
}

/** Un solo percorso di costo minimo secondo [p]. [penalty]: moltiplicatore
 * del costo per arco (id arco reale -> fattore >= 1), usato per ottenere
 * percorsi alternativi SENZA vietare archi (vedi [findAppRoutes]). */
private fun findWeightedAppRoute(
    filtered: FilteredGraph,
    start: LatLon,
    end: LatLon,
    p: AppRoutingParams,
    penalty: Map<Int, Double>,
): RouteResult? {
    val (startSnap, endSnap) = snapPairToGraph(filtered, start, end) ?: return null
    // Partenza e arrivo sullo stesso arco: nessuna scelta da pesare.
    if (startSnap.edgeId == endSnap.edgeId) {
        return findRoute(filtered, start, end, p.engine)
    }
    val query = spliceEndpoints(filtered, startSnap, endSnap)
    fun sourceWeight(source: String) = sourceWeightOf(source, p)
    val cost: (Int, Int, Int) -> Double = { edgeId, from, to ->
        if (edgeId >= 0) {
            val e = filtered.graph.edges[edgeId]
            (p.pesoKm * e.lengthM * sourceWeight(e.source) + p.pesoSalita * e.climbFrom(from)) *
                (penalty[edgeId] ?: 1.0)
        } else {
            val ve = query.virtualEdges.getValue(edgeId)
            val realSource = filtered.graph.edges[ve.realEdgeId].source
            // ve.ele e' orientata dal nodo virtuale verso l'estremo reale
            val ele = ve.ele?.let { if (from >= query.nodeCount) it else it.reversed() }
            (p.pesoKm * ve.length * sourceWeight(realSource) + p.pesoSalita * climbOf(ele)) *
                (penalty[ve.realEdgeId] ?: 1.0)
        }
    }
    val traversed = solveWeighted(filtered, query, emptySet(), p.engine, cost) ?: return null
    val steps = buildSteps(filtered, query, traversed)
    return RouteResult(steps, steps.sumOf { it.lengthM }, startSnap, endSnap)
}

/**
 * Fino a [AppRoutingParams.numeroPercorsi] percorsi diversi, etichettati
 * 1..N: il migliore secondo il costo pesato, poi i successivi.
 *
 * (2026-10-17, baco trovato coi test di Lorenzo) Prima: un candidato scartato
 * da una valvola faceva VIETARE tutti i suoi archi, compresi i tratti buoni
 * (es. l'arrivo), e il tentativo seguente era costretto a giri assurdi; se
 * tutti fallivano si ripiegava sul piu' breve in km, che ignora i pesi
 * SIC/raccordi. Ora: gli archi di ogni candidato (accettato o scartato)
 * vengono solo PENALIZZATI (costo x [PENALITA_ALTERNATIVE], cumulativo),
 * quindi restano usabili se servono; se nessun candidato rispetta le
 * valvole, resta il migliore secondo il costo pesato (il primo candidato),
 * non il piu' breve in km. Doppioni scartati.
 */
fun findAppRoutes(filtered: FilteredGraph, start: LatLon, end: LatLon, p: AppRoutingParams): List<LabeledRoute> {
    val baseline = findRoute(filtered, start, end, p.engine) ?: return emptyList()
    val capM = baseline.totalLengthM * (1.0 + p.margineMassimoPercent / 100.0)
    val backwardLimitM = p.arretramentoMassimoPercent / 100.0 * haversineMeters(start, end)
    val n = p.numeroPercorsi.coerceAtLeast(1)

    val out = mutableListOf<LabeledRoute>()
    val seen = mutableSetOf<List<Int>>()
    val penalty = HashMap<Int, Double>()
    var primo: RouteResult? = null
    var guard = 0
    while (out.size < n && guard < n + 6) {
        guard++
        val candidate = findWeightedAppRoute(filtered, start, end, p, penalty) ?: break
        if (primo == null) primo = candidate
        val ids = candidate.steps.map { it.edgeId }
        val ok = candidate.totalLengthM <= capM && backwardExcursionM(candidate, start, end) <= backwardLimitM
        if (ok && ids !in seen) {
            seen.add(ids)
            out.add(LabeledRoute(out.size + 1, RouteRule.PESATO, candidate))
        }
        for (id in ids.toSet()) penalty[id] = (penalty[id] ?: 1.0) * PENALITA_ALTERNATIVE
    }
    if (out.isEmpty()) out.add(LabeledRoute(1, RouteRule.PESATO, primo ?: baseline))
    return out
}

/** Moltiplicatore del costo degli archi di un candidato gia' visto, per far
 * emergere il percorso alternativo successivo (penalita', non divieto). */
private const val PENALITA_ALTERNATIVE = 2.0
