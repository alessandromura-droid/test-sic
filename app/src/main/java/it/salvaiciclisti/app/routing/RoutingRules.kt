package it.salvaiciclisti.app.routing

import java.util.PriorityQueue
import kotlin.math.cos

/**
 * Le tre euristiche di preferenza SIC discusse con Alessandro il
 * 2026-10-02, ciascuna con la propria "valvola di sicurezza" contro il
 * comportamento patologico di un peso lineare puro (un peso alto produce
 * giri assurdi perche' il costo cresce senza limite con la lunghezza; un
 * peso medio perde comunque contro una sorgente piu' diffusa) - la
 * preferenza guida la ricerca, ma se il risultato viola la propria valvola
 * si usa il percorso piu' breve assoluto invece di un percorso palesemente
 * fuori misura:
 * - [BUDGET_CAP]: la lunghezza totale non puo' superare quella del percorso
 *   piu' breve assoluto oltre [RuleParams.budgetCapPercent].
 * - [SOURCE_SURCHARGE]: supplemento FISSO per arco non preferito (non per
 *   metro, non per numero di cambi espliciti) - un'approssimazione
 *   deliberata di "penalita' per cambio sorgente": tracciare i cambi veri
 *   richiederebbe duplicare lo stato per nodo (nodo, ultima sorgente),
 *   cioe' riscrivere splice/orientamento una seconda volta per una delicata
 *   parte di codice che ha gia' avuto un bug reale (vedi il commento sul
 *   salto di 8+ km in Router.kt); il supplemento fisso per arco cattura
 *   comunque l'intento (scoraggia frammentare su molti tratti brevi non
 *   preferiti) con un rischio molto piu' basso.
 * - [DIRECTIONAL]: il percorso non puo' allontanarsi complessivamente
 *   dall'asse partenza->arrivo oltre [RuleParams.directionalBackwardPercent]
 *   (vedi [backwardExcursionM]).
 */
enum class RouteRule { BUDGET_CAP, SOURCE_SURCHARGE, DIRECTIONAL }

/** Tutti i parametri tunabili delle tre regole, pensati per finire in
 * Impostazioni (vedi SettingsRepository/SettingsScreen) cosi' Alessandro
 * puo' tararli senza toccare il codice. */
data class RuleParams(
    val preferredSourceKey: String = "salvaiciclisti_sicrm",
    val budgetCapPercent: Double = 15.0,
    val budgetSicWeight: Double = 3.0,
    val sourceSurchargeM: Double = 300.0,
    val directionalBackwardPercent: Double = 10.0,
    val directionalSicWeight: Double = 3.0,
)

data class LabeledRoute(val label: Int, val rule: RouteRule, val result: RouteResult)

/**
 * Fino a [routesPerRule] percorsi per ciascuna delle tre regole sopra (fino
 * a 3*[routesPerRule] in tutto - con [routesPerRule]=2, le "fino a 6
 * alternative" concordate), etichettati (1)(2)(3)... nell'ordine in cui
 * vengono prodotti, rule per rule. Il primo di ogni regola e' il "migliore
 * secondo quella regola"; i successivi si ottengono bandendo gli archi del
 * precedente, come in [findAlternativeRoutes] - un doppione (stessa identica
 * sequenza di archi gia' presente, incluso tra regole diverse) viene
 * scartato senza contare come uno dei [routesPerRule] di quella regola.
 *
 * Null/vuoto se nemmeno il percorso piu' breve assoluto esiste (partenza o
 * arrivo non raggiungibili su nessuna componente comune, vedi
 * [snapPairToGraph]).
 */
fun findRuledRoutes(
    filtered: FilteredGraph,
    start: LatLon,
    end: LatLon,
    engine: RoutingEngine,
    routesPerRule: Int,
    params: RuleParams,
): List<LabeledRoute> {
    val baseline = findRoute(filtered, start, end, engine) ?: return emptyList()
    val backwardLimitM = params.directionalBackwardPercent / 100.0 * haversineMeters(start, end)
    val out = mutableListOf<LabeledRoute>()
    var nextLabel = 1

    fun weightedCost(preferredWeight: Double): (RoutingEdge) -> Double = { e ->
        if (e.source == params.preferredSourceKey) e.lengthM else e.lengthM * preferredWeight
    }
    val surchargeCost: (RoutingEdge) -> Double = { e ->
        e.lengthM + if (e.source == params.preferredSourceKey) 0.0 else params.sourceSurchargeM
    }

    fun runRule(rule: RouteRule, costFn: (RoutingEdge) -> Double, accept: (RouteResult) -> Boolean) {
        val banned = mutableSetOf<Int>()
        val seen = mutableSetOf<List<Int>>()
        var produced = 0
        var guard = 0
        while (produced < routesPerRule && guard < routesPerRule + 4) {
            guard++
            val candidate = findWeightedRoute(filtered, start, end, banned, costFn)
            val chosen = if (candidate != null && accept(candidate)) candidate else baseline
            val key = chosen.steps.map { it.edgeId }
            if (seen.add(key)) {
                out.add(LabeledRoute(nextLabel++, rule, chosen))
                produced++
            }
            if (candidate == null) return
            banned.addAll(candidate.steps.map { it.edgeId })
        }
    }

    val budgetCapM = baseline.totalLengthM * (1.0 + params.budgetCapPercent / 100.0)
    runRule(RouteRule.BUDGET_CAP, weightedCost(params.budgetSicWeight)) { it.totalLengthM <= budgetCapM }
    runRule(RouteRule.SOURCE_SURCHARGE, surchargeCost) { true }
    runRule(RouteRule.DIRECTIONAL, weightedCost(params.directionalSicWeight)) {
        backwardExcursionM(it, start, end) <= backwardLimitM
    }

    return out
}

/**
 * Cammino piu' breve usando [costFn] al posto della lunghezza fisica per
 * ogni arco reale - gli archi sintetici di splice usano sempre la loro vera
 * lunghezza fisica (sono tronconi minuscoli dell'arco su cui cade lo snap,
 * pesarli diversamente distorcerebbe lo snap stesso senza alcun senso).
 * Condivide snap/splice/orientamento con [findRoute] ([snapPairToGraph],
 * [spliceEndpoints], [buildSteps] in Router.kt, resi internal apposta): solo
 * l'algoritmo di confronto costi cambia, mai la geometria - una Dijkstra a
 * doppio accumulo (costo per l'ordinamento in coda, lunghezza vera per il
 * risultato) perche' [RouteResult.totalLengthM] deve restare una distanza
 * reale in metri, non la somma dei pesi di preferenza.
 */
private fun findWeightedRoute(
    filtered: FilteredGraph,
    start: LatLon,
    end: LatLon,
    bannedEdges: Set<Int>,
    costFn: (RoutingEdge) -> Double,
): RouteResult? {
    val (startSnap, endSnap) = snapPairToGraph(filtered, start, end) ?: return null
    val query = spliceEndpoints(filtered, startSnap, endSnap)

    fun cost(edgeId: Int): Double =
        if (edgeId >= 0) costFn(filtered.graph.edges[edgeId]) else query.virtualEdges.getValue(edgeId).length

    fun trueLength(edgeId: Int): Double =
        if (edgeId >= 0) filtered.graph.edges[edgeId].lengthM else query.virtualEdges.getValue(edgeId).length

    val distCost = HashMap<Int, Double>()
    val distTrue = HashMap<Int, Double>()
    val prevNode = HashMap<Int, Int>()
    val prevEdge = HashMap<Int, Int>()
    distCost[query.startNode] = 0.0
    distTrue[query.startNode] = 0.0
    val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
    pq.add(0.0 to query.startNode)
    val visited = HashSet<Int>()

    while (pq.isNotEmpty()) {
        val (d, node) = pq.poll()
        if (!visited.add(node)) continue
        if (node == query.endNode) break
        for ((edgeId, other) in neighborsOf(filtered, query, node)) {
            if (edgeId >= 0 && edgeId in bannedEdges) continue
            val newCost = d + cost(edgeId)
            if (newCost < (distCost[other] ?: Double.MAX_VALUE)) {
                distCost[other] = newCost
                distTrue[other] = distTrue.getValue(node) + trueLength(edgeId)
                prevNode[other] = node
                prevEdge[other] = edgeId
                pq.add(newCost to other)
            }
        }
    }

    if (query.endNode !in distCost) return null

    val traversed = mutableListOf<TraversedEdge>()
    var cur = query.endNode
    while (cur != query.startNode) {
        val edgeId = prevEdge.getValue(cur)
        val from = prevNode.getValue(cur)
        traversed.add(TraversedEdge(edgeId, from, cur))
        cur = from
    }
    traversed.reverse()

    val steps = buildSteps(filtered, query, traversed)
    return RouteResult(steps, distTrue.getValue(query.endNode), startSnap, endSnap)
}

/**
 * Quanto il percorso si allontana complessivamente dall'asse [start]->[end]:
 * per ogni tratto di geometria, la componente della sua lunghezza lungo
 * quell'asse (via [bearing], non un'approssimazione in gradi - a Roma un
 * grado di longitudine e uno di latitudine non sono la stessa distanza in
 * metri); un tratto che avanza verso [end] non conta nulla, uno che arretra
 * si somma in positivo - una rotonda o un breve attraversamento restano
 * quindi "gratis" finche' il percorso nel complesso riprende ad avanzare.
 * Valutata DOPO aver trovato il cammino (valvola di sicurezza), mai come
 * costo durante la ricerca.
 */
private fun backwardExcursionM(route: RouteResult, start: LatLon, end: LatLon): Double {
    if (haversineMeters(start, end) < 1.0) return 0.0
    val axisBearing = bearing(start, end)
    var backward = 0.0
    for (step in route.steps) {
        for ((a, b) in step.geom.zipWithNext()) {
            val segLen = haversineMeters(a, b)
            if (segLen < 0.01) continue
            val forward = segLen * cos(Math.toRadians(bearing(a, b) - axisBearing))
            if (forward < 0) backward += -forward
        }
    }
    return backward
}
