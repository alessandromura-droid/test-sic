package it.salvaiciclisti.app.routing

import java.util.PriorityQueue
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.WeightedMultigraph

/** Motore di ricerca del cammino piu' breve. IN_HOUSE e' la Dijkstra scritta
 * a mano in questo file; JGRAPHT usa la libreria JGraphT (Apache/EPL,
 * dipendenza libera, nessun problema di licenza) per la sola ricerca del
 * cammino - la costruzione del grafo di query (snap, splice dei nodi
 * virtuali) e la geometria restano identiche, condivise da entrambi i
 * motori, cosi' un confronto fra i due isola davvero solo l'algoritmo di
 * shortest-path, non differenze accidentali altrove. Introdotto per dare
 * all'utente la scelta (Impostazioni) e per verificare con mano se una
 * libreria matura ed esterna produce risultati diversi/migliori di quella
 * scritta in casa. */
enum class RoutingEngine { IN_HOUSE, JGRAPHT }

/** One graph edge traversed by a route, in the direction actually travelled,
 * with geometry oriented that way and - for the partial hop right at the
 * start/end of a route - trimmed to only the travelled part of that edge. */
data class RouteStep(
    val edgeId: Int,
    val name: String?,
    val source: String,
    val lengthM: Double,
    val geom: List<LatLon>,
)

data class RouteResult(
    val steps: List<RouteStep>,
    val totalLengthM: Double,
    val startSnap: GraphSnap,
    val endSnap: GraphSnap,
)

private const val UNREACHABLE = Double.MAX_VALUE / 4

/** Un arco reale (id >= 0) o sintetico/virtuale (id < 0, solo per la query
 * corrente - vedi [spliceEndpoints]), con i due nodi nell'ordine in cui e'
 * stato effettivamente attraversato dal cammino trovato: serve a orientare
 * correttamente la geometria in [buildSteps], che altrimenti userebbe
 * sempre l'ordine "di archivio" (a->b) anche quando il cammino lo percorre
 * al contrario - il bug del salto di 8+ km scoperto il 2026-09-18 (arco
 * lungo percorso a ritroso, geometria non invertita). */
internal data class TraversedEdge(val edgeId: Int, val from: Int, val to: Int)

internal data class VirtualEdge(val realEdgeId: Int, val length: Double, val geom: List<LatLon>)

/** Grafo di query per UNA singola ricerca: [filtered] piu' fino a due nodi
 * virtuali (uno per capo, se lo snap cade a meta' di un arco anziche' su un
 * suo estremo reale) e i relativi archi sintetici. Costruito una volta sola
 * e poi passato a QUALUNQUE motore (IN_HOUSE o JGRAPHT), cosi' i due
 * condividono esattamente lo stesso snap/splice - solo l'algoritmo di
 * ricerca cambia. */
internal class RoutingQuery(
    val nodeCount: Int,
    val totalNodes: Int,
    val startNode: Int,
    val endNode: Int,
    val virtualEdges: Map<Int, VirtualEdge>,
    val extra: Map<Int, List<Pair<Int, Int>>>,
)

/**
 * Snappa [start]/[end] su [filtered] e li innesta come nodi virtuali,
 * cablati solo ai due estremi reali dell'arco su cui cadono - cosi'
 * partire/arrivare a meta' di un arco lungo non forza una deviazione fino a
 * uno dei suoi estremi. Ritorna null se uno dei due punti non snappa su
 * nessun arco attivo.
 */
internal fun spliceEndpoints(filtered: FilteredGraph, startSnap: GraphSnap, endSnap: GraphSnap): RoutingQuery {
    val nodeCount = filtered.graph.nodes.size
    var nextVirtualId = nodeCount
    var nextSyntheticEdgeId = -1

    val virtualEdges = HashMap<Int, VirtualEdge>()
    val extra = HashMap<Int, MutableList<Pair<Int, Int>>>()

    fun addExtra(node: Int, edgeId: Int, other: Int) {
        extra.getOrPut(node) { mutableListOf() }.add(edgeId to other)
    }

    fun spliceEndpoint(snap: GraphSnap): Int {
        val edge = filtered.graph.edges[snap.edgeId]
        if (snap.distanceAlongEdgeM <= 0.5) return edge.a
        if (edge.lengthM - snap.distanceAlongEdgeM <= 0.5) return edge.b

        val vNode = nextVirtualId++
        // vertici 0..segmentIndex stanno sul lato A del punto di snap,
        // segmentIndex+1..fondo stanno sul lato B (lo snap stesso cade tra
        // geom[segmentIndex] e geom[segmentIndex+1]). Entrambe le liste
        // vanno lette DAL PUNTO DI SNAP verso l'estremo corrispondente (lo
        // stesso schema di toB, punto di snap per primo): un bug precedente
        // qui metteva lo snap.point in coda invece che in testa per toA,
        // producendo un salto rettilineo spurio fine-arco -> snap invece di
        // seguire la curva reale dell'arco fino allo snap.
        val toA = listOf(snap.point) + edge.geom.subList(0, snap.segmentIndex + 1).reversed()
        val toB = listOf(snap.point) + edge.geom.subList(snap.segmentIndex + 1, edge.geom.size)

        val lenToA = snap.distanceAlongEdgeM
        val lenToB = edge.lengthM - snap.distanceAlongEdgeM

        val idToA = nextSyntheticEdgeId--
        val idToB = nextSyntheticEdgeId--
        // Geometria orientata "dal nodo virtuale verso l'estremo reale" -
        // se il cammino percorre l'arco sintetico nel verso opposto (dal
        // vero estremo verso il nodo virtuale, il caso tipico quando il
        // punto di ARRIVO cade a meta' arco), buildSteps() la inverte, come
        // fa per gli archi reali.
        virtualEdges[idToA] = VirtualEdge(snap.edgeId, lenToA, toA)
        virtualEdges[idToB] = VirtualEdge(snap.edgeId, lenToB, toB)

        addExtra(vNode, idToA, edge.a)
        addExtra(edge.a, idToA, vNode)
        addExtra(vNode, idToB, edge.b)
        addExtra(edge.b, idToB, vNode)

        return vNode
    }

    val startNode = spliceEndpoint(startSnap)
    val endNode = spliceEndpoint(endSnap)
    return RoutingQuery(nodeCount, nextVirtualId, startNode, endNode, virtualEdges, extra)
}

internal fun neighborsOf(filtered: FilteredGraph, query: RoutingQuery, n: Int): List<Pair<Int, Int>> {
    val base: List<Pair<Int, Int>> = if (n < query.nodeCount) filtered.adjacency[n] else emptyList()
    val extras = query.extra[n] ?: emptyList()
    return if (extras.isEmpty()) base else base + extras
}

private fun edgeLength(filtered: FilteredGraph, query: RoutingQuery, edgeId: Int): Double =
    if (edgeId >= 0) filtered.graph.edges[edgeId].lengthM else query.virtualEdges.getValue(edgeId).length

/** Dijkstra scritta a mano, con coda a priorita' - il motore "storico",
 * usato di default finche' JGraphT non e' stato validato a sufficienza. */
private fun solveInHouse(
    filtered: FilteredGraph,
    query: RoutingQuery,
    bannedEdges: Set<Int>,
): Pair<List<TraversedEdge>, Double>? {
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
            val newDist = d + edgeLength(filtered, query, edgeId)
            if (newDist < (dist[other] ?: UNREACHABLE)) {
                dist[other] = newDist
                prevNode[other] = node
                prevEdge[other] = edgeId
                pq.add(newDist to other)
            }
        }
    }

    if (query.endNode !in dist) return null

    val traversed = mutableListOf<TraversedEdge>()
    var cur = query.endNode
    while (cur != query.startNode) {
        val edgeId = prevEdge.getValue(cur)
        val from = prevNode.getValue(cur)
        traversed.add(TraversedEdge(edgeId, from, cur))
        cur = from
    }
    traversed.reverse()
    return traversed to dist.getValue(query.endNode)
}

/** Stesso identico grafo di query (nodi, archi reali attivi + archi
 * sintetici della splice), ma la ricerca del cammino piu' breve la fa
 * JGraphT (DijkstraShortestPath su un WeightedMultigraph - Multigraph
 * perche' il grafo reale puo' avere piu' di un arco tra la stessa coppia di
 * nodi, es. due piste indipendenti che uniscono gli stessi due incroci: un
 * grafo "semplice" li fonderebbe/rifiuterebbe). GraphPath.vertexList/
 * edgeList di JGraphT sono gia' ordinati lungo il cammino, quindi qui non
 * serve nessuna ricostruzione a ritroso (prevNode/prevEdge) come nel motore
 * IN_HOUSE. */
private fun solveJGraphT(
    filtered: FilteredGraph,
    query: RoutingQuery,
    bannedEdges: Set<Int>,
): Pair<List<TraversedEdge>, Double>? {
    data class QEdge(val edgeId: Int, val length: Double)

    val graph = WeightedMultigraph<Int, QEdge>(QEdge::class.java)
    for (n in 0 until query.totalNodes) graph.addVertex(n)

    val addedReal = HashSet<Int>()
    for (n in 0 until query.nodeCount) {
        for ((edgeId, other) in filtered.adjacency[n]) {
            if (edgeId in bannedEdges) continue
            // Arco ad anello (stesso nodo ai due capi, 2026-10-13): inutile per
            // un percorso minimo e rifiutato da WeightedMultigraph ("loops not
            // allowed") - si salta.
            if (other == n) continue
            if (!addedReal.add(edgeId)) continue
            val qe = QEdge(edgeId, edgeLength(filtered, query, edgeId))
            graph.addEdge(n, other, qe)
            graph.setEdgeWeight(qe, qe.length)
        }
    }
    val addedVirtual = HashSet<Int>()
    for ((n, list) in query.extra) {
        for ((edgeId, other) in list) {
            if (other == n) continue  // anello: vedi sopra
            if (!addedVirtual.add(edgeId)) continue
            val qe = QEdge(edgeId, edgeLength(filtered, query, edgeId))
            graph.addEdge(n, other, qe)
            graph.setEdgeWeight(qe, qe.length)
        }
    }

    val path = DijkstraShortestPath(graph).getPath(query.startNode, query.endNode) ?: return null
    val vertices = path.vertexList
    val traversed = path.edgeList.mapIndexed { i, qe -> TraversedEdge(qe.edgeId, vertices[i], vertices[i + 1]) }
    return traversed to path.weight
}

/** Da (arco, nodo di partenza, nodo di arrivo) a [RouteStep] con la
 * geometria orientata nel verso di percorrenza reale - condiviso da
 * entrambi i motori, cosi' un bug di orientamento va sistemato in un solo
 * posto invece che in due implementazioni parallele. */
internal fun buildSteps(filtered: FilteredGraph, query: RoutingQuery, traversed: List<TraversedEdge>): List<RouteStep> =
    traversed.map { (edgeId, from, to) ->
        if (edgeId >= 0) {
            val e = filtered.graph.edges[edgeId]
            val geom = when {
                e.a == from && e.b == to -> e.geom
                e.b == from && e.a == to -> e.geom.reversed()
                else -> error(
                    "arco $edgeId (a=${e.a}, b=${e.b}) non collega $from -> $to: " +
                        "grafo o ricostruzione del cammino inconsistenti",
                )
            }
            RouteStep(edgeId, e.name, e.source, e.lengthM, geom)
        } else {
            val ve = query.virtualEdges.getValue(edgeId)
            val realEdge = filtered.graph.edges[ve.realEdgeId]
            // ve.geom e' orientata "dal nodo virtuale verso l'estremo reale"
            // (vedi spliceEndpoints, dove il nodo virtuale e' sempre l'unico
            // dei due con id >= nodeCount). Se il cammino la percorre in
            // quel verso (parte proprio dal nodo virtuale) va bene com'e',
            // altrimenti va invertita - stessa identica logica usata sopra
            // per gli archi reali, solo che qui l'orientamento "di
            // archivio" e' "da virtuale a reale" invece che "da a a b".
            val geom = if (from >= query.nodeCount) ve.geom else ve.geom.reversed()
            RouteStep(ve.realEdgeId, realEdge.name, realEdge.source, ve.length, geom)
        }
    }

/**
 * Cammino piu' breve da [start] a [end], entrambi snappati sull'arco attivo
 * piu' vicino tra le sorgenti abilitate in [filtered] - per specifica, il
 * routing usa ESCLUSIVAMENTE quelle sorgenti, mai una rete stradale
 * generica di supporto (per quanto disconnesso questo lasci alcuni
 * indirizzi: gestito un livello sopra, snappando al punto raggiungibile
 * piu' vicino invece di fallire e basta).
 *
 * [engine] sceglie l'algoritmo di ricerca (vedi [RoutingEngine]); a parita'
 * di grafo e di snap, i due motori devono trovare lo stesso cammino (a meno
 * di pareggi tra piu' cammini ugualmente corti) - la differenza e' solo
 * quale codice fa la ricerca.
 *
 * [bannedEdges] lets [findAlternativeRoutes] forbid edges used by an
 * earlier result, the simplest way to get genuinely different alternatives
 * out of a plain shortest-path search.
 */
fun findRoute(
    filtered: FilteredGraph,
    start: LatLon,
    end: LatLon,
    engine: RoutingEngine = RoutingEngine.IN_HOUSE,
    bannedEdges: Set<Int> = emptySet(),
): RouteResult? {
    val (startSnap, endSnap) = snapPairToGraph(filtered, start, end) ?: return null

    // Short trip entirely within one edge: handle directly (identico per
    // entrambi i motori, non serve nessuna ricerca su grafo), la pipeline
    // generale sotto non collega mai due nodi virtuali sullo stesso arco tra
    // loro (solo ai due estremi reali di quell'arco), quindi altrimenti
    // forzerebbe una deviazione inutile fino a un estremo.
    if (startSnap.edgeId == endSnap.edgeId && startSnap.edgeId !in bannedEdges) {
        val edge = filtered.graph.edges[startSnap.edgeId]
        val forward = startSnap.distanceAlongEdgeM <= endSnap.distanceAlongEdgeM
        val near = if (forward) startSnap else endSnap
        val far = if (forward) endSnap else startSnap
        val length = far.distanceAlongEdgeM - near.distanceAlongEdgeM
        if (length > 0.0) {
            val interior = if (far.segmentIndex > near.segmentIndex) {
                edge.geom.subList(near.segmentIndex + 1, far.segmentIndex + 1)
            } else {
                emptyList()
            }
            val geomSlice = listOf(near.point) + interior + listOf(far.point)
            val step = RouteStep(startSnap.edgeId, edge.name, edge.source, length, geomSlice)
            return RouteResult(listOf(step), length, startSnap, endSnap)
        }
    }

    val query = spliceEndpoints(filtered, startSnap, endSnap)
    val solved = when (engine) {
        RoutingEngine.IN_HOUSE -> solveInHouse(filtered, query, bannedEdges)
        RoutingEngine.JGRAPHT -> solveJGraphT(filtered, query, bannedEdges)
    } ?: return null
    val (traversed, totalLength) = solved

    val steps = buildSteps(filtered, query, traversed)
    return RouteResult(steps, totalLength, startSnap, endSnap)
}

/**
 * Best route plus up to [maxAlternatives] more, found by banning the real
 * edges of the previous result and re-running Dijkstra - a simple, well
 * known way to get genuinely different alternatives without implementing a
 * dedicated k-shortest-paths algorithm. A candidate sharing 85% or more of
 * its edges with the previous one is dropped as not meaningfully different.
 */
fun findAlternativeRoutes(
    filtered: FilteredGraph,
    start: LatLon,
    end: LatLon,
    engine: RoutingEngine = RoutingEngine.IN_HOUSE,
    maxAlternatives: Int = 2,
): List<RouteResult> {
    val best = findRoute(filtered, start, end, engine) ?: return emptyList()
    val results = mutableListOf(best)
    val banned = mutableSetOf<Int>()

    repeat(maxAlternatives) {
        banned.addAll(results.last().steps.map { it.edgeId })
        val candidate = findRoute(filtered, start, end, engine, banned) ?: return@repeat
        val previousEdges = results.last().steps.map { it.edgeId }.toSet()
        val candidateEdges = candidate.steps.map { it.edgeId }.toSet()
        val overlap = candidateEdges.intersect(previousEdges).size.toDouble() / candidateEdges.size.coerceAtLeast(1)
        if (overlap < 0.85) {
            results.add(candidate)
        }
    }
    return results
}
