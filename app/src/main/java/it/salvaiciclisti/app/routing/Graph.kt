package it.salvaiciclisti.app.routing

// GraphLoader (sotto) legge graph.json con un parser suo, non con SimpleJson:
// vedi il suo commento per il perche'. Questi restano solo per
// SourcesManifestLoader, che legge il piccolo sources_manifest.json.
import it.salvaiciclisti.app.json.SimpleJson
import it.salvaiciclisti.app.json.arr
import it.salvaiciclisti.app.json.num
import it.salvaiciclisti.app.json.obj
import it.salvaiciclisti.app.json.str

/**
 * One traversable stretch of the routing graph, built from a single source's
 * vector data (scripts/build_routing_graph.py) - see [source] for which of
 * the "3+3" collected sources it came from. [geom] always runs from node [a]
 * to node [b] in that order (never reversed), full resolution, used both for
 * routing math and for drawing the route on the map.
 */
class RoutingEdge(
    val id: Int,
    val a: Int,
    val b: Int,
    val lengthM: Double,
    val source: String,
    val name: String?,
    val geom: List<LatLon>,
)

/**
 * The full offline routing graph, assembled once at build time
 * (scripts/build_routing_graph.py) from every enabled-at-build-time source
 * and downloaded by the app from GitHub Releases (RemoteMapDataRepository,
 * not bundled in the APK - see Alessandro, 2026-09-21). Which sources
 * actually participate in a given route search is a runtime choice (the
 * Settings screen), handled by [FilteredGraph] - this class holds the union
 * of everything, tagged by source, so that filter is just "which edges to
 * skip", not a different asset per combination of enabled sources.
 */
class RoutingGraph(val nodes: List<LatLon>, val edges: List<RoutingEdge>)

/**
 * Reads graph.json ({"nodes": [[lat,lon],...], "edges":
 * [{"a","b","len","src","name","geom"},...]}, see build_routing_graph.py).
 * Un JSON qualunque, non binario - ma letto con un parser scritto apposta
 * per questo schema, che scrive direttamente in RoutingEdge/LatLon SENZA
 * passare da un albero JSON generico intermedio (a differenza di
 * [it.salvaiciclisti.app.json.SimpleJson], usato per gli asset piccoli):
 * confermato da crash reale sul telefono (OutOfMemoryError in
 * SimpleJson$Parser.parseArray, 2026-09-18) che con ~143mila archi e oltre
 * mezzo milione di punti di geometria, l'albero intermedio (una mappa per
 * ogni oggetto, un Double boxed per ogni numero) supera l'heap di 256MB
 * dell'app su questo telefono - non c'entra ne' OSM ne' un formato binario,
 * e' proprio la dimensione attuale del grafo con un parser ad albero
 * generico, qualunque sia il file su cui lo si punta. */
object GraphLoader {
    fun parse(jsonText: String): RoutingGraph = GraphJsonParser(jsonText).parseGraph()
}

private class GraphJsonParser(val s: String) {
    var i = 0

    fun parseGraph(): RoutingGraph {
        skipWs(); expectChar('{'); skipWs()
        var nodes: List<LatLon> = emptyList()
        var edges: List<RoutingEdge> = emptyList()
        if (peek() == '}') { i++; return RoutingGraph(nodes, edges) }
        while (true) {
            skipWs()
            when (val key = parseJsonString()) {
                "nodes" -> { skipWs(); expectChar(':'); skipWs(); nodes = parseLatLonArray() }
                "edges" -> { skipWs(); expectChar(':'); skipWs(); edges = parseEdgesArray() }
                else -> { skipWs(); expectChar(':'); skipWs(); skipValue() }
            }
            skipWs()
            when (s[i]) {
                ',' -> { i++ }
                '}' -> { i++; break }
                else -> parseError("atteso ',' o '}'")
            }
        }
        return RoutingGraph(nodes, edges)
    }

    private fun parseLatLonArray(): List<LatLon> {
        expectChar('['); skipWs()
        val list = ArrayList<LatLon>()
        if (peek() == ']') { i++; return list }
        while (true) {
            skipWs(); expectChar('['); skipWs()
            val lat = parseNumber(); skipWs(); expectChar(','); skipWs()
            val lon = parseNumber(); skipWs(); expectChar(']')
            list.add(LatLon(lat, lon))
            skipWs()
            when (s[i]) {
                ',' -> { i++; skipWs() }
                ']' -> { i++; break }
                else -> parseError("atteso ',' o ']' in un array di coordinate")
            }
        }
        return list
    }

    private fun parseEdgesArray(): List<RoutingEdge> {
        expectChar('['); skipWs()
        val list = ArrayList<RoutingEdge>()
        if (peek() == ']') { i++; return list }
        var idx = 0
        while (true) {
            skipWs()
            list.add(parseEdgeObject(idx++))
            skipWs()
            when (s[i]) {
                ',' -> { i++; skipWs() }
                ']' -> { i++; break }
                else -> parseError("atteso ',' o ']' in edges")
            }
        }
        return list
    }

    private fun parseEdgeObject(id: Int): RoutingEdge {
        expectChar('{'); skipWs()
        var a = 0
        var b = 0
        var len = 0.0
        var src = ""
        var name: String? = null
        var geom: List<LatLon> = emptyList()
        if (peek() != '}') {
            while (true) {
                skipWs()
                val key = parseJsonString()
                skipWs(); expectChar(':'); skipWs()
                when (key) {
                    "a" -> a = parseNumber().toInt()
                    "b" -> b = parseNumber().toInt()
                    "len" -> len = parseNumber()
                    "src" -> src = parseJsonString()
                    "name" -> name = if (s[i] == 'n') { expectWord("null"); null } else parseJsonString()
                    "geom" -> geom = parseLatLonArray()
                    else -> skipValue()
                }
                skipWs()
                when (s[i]) {
                    ',' -> { i++ }
                    '}' -> { i++; break }
                    else -> parseError("atteso ',' o '}' in un arco")
                }
            }
        } else {
            i++
        }
        return RoutingEdge(id, a, b, len, src, name, geom)
    }

    /** Consuma e scarta un valore JSON qualunque - tolleranza per campi non
     * attesi, che con lo schema attuale non si presentano mai. */
    private fun skipValue() {
        skipWs()
        when (s[i]) {
            '{' -> {
                i++; skipWs()
                if (s[i] == '}') { i++ } else {
                    while (true) {
                        skipWs(); parseJsonString(); skipWs(); expectChar(':'); skipValue(); skipWs()
                        if (s[i] == ',') { i++ } else { expectChar('}'); break }
                    }
                }
            }
            '[' -> {
                i++; skipWs()
                if (s[i] == ']') { i++ } else {
                    while (true) {
                        skipValue(); skipWs()
                        if (s[i] == ',') { i++; skipWs() } else { expectChar(']'); break }
                    }
                }
            }
            '"' -> parseJsonString()
            't' -> expectWord("true")
            'f' -> expectWord("false")
            'n' -> expectWord("null")
            else -> parseNumber()
        }
    }

    private fun parseJsonString(): String {
        expectChar('"')
        val sb = StringBuilder()
        while (s[i] != '"') {
            val c = s[i]
            if (c == '\\') {
                i++
                when (s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 1, i + 5)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> parseError("escape non valido")
                }
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        i++
        return sb.toString()
    }

    private fun parseNumber(): Double {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
        return s.substring(start, i).toDouble()
    }

    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun peek(): Char = s[i]

    private fun expectChar(c: Char) {
        if (i >= s.length || s[i] != c) parseError("atteso '$c'")
        i++
    }

    private fun expectWord(word: String) {
        if (i + word.length > s.length || s.substring(i, i + word.length) != word) parseError("atteso '$word'")
        i += word.length
    }

    private fun parseError(msg: String): Nothing =
        throw IllegalStateException("graph.json malformato alla posizione $i: $msg")
}

data class SourceInfo(val key: String, val label: String, val edgeCount: Int)

object SourcesManifestLoader {
    fun parse(jsonText: String): List<SourceInfo> {
        val root = SimpleJson.parse(jsonText).obj()
        return root.getValue("sources").arr().map { s ->
            val o = s.obj()
            SourceInfo(
                key = o.getValue("key").str(),
                label = o.getValue("label").str(),
                edgeCount = o.getValue("edges").num().toInt(),
            )
        }
    }
}

/**
 * A view of [graph] restricted to edges whose source is in [enabledSources] -
 * rebuilt only when the user changes Settings, not on every route query.
 * Nodes that end up with no active edges are simply never visited by
 * Dijkstra; they don't need to be removed explicitly.
 */
class FilteredGraph(val graph: RoutingGraph, val enabledSources: Set<String>) {
    val activeEdgeIds: List<Int> = graph.edges.indices.filter { graph.edges[it].source in enabledSources }
    val adjacency: Array<MutableList<Pair<Int, Int>>> = run {
        val adj = Array(graph.nodes.size) { mutableListOf<Pair<Int, Int>>() }
        for (ei in activeEdgeIds) {
            val e = graph.edges[ei]
            adj[e.a].add(ei to e.b)
            adj[e.b].add(ei to e.a)
        }
        adj
    }

    /** Etichetta ogni nodo con l'id della sua componente connessa dentro gli
     * archi attivi - ricalcolata solo quando cambiano le sorgenti abilitate
     * (una [FilteredGraph] nuova per ogni combinazione, vedi MapScreen), non
     * per ogni ricerca di percorso: costo trascurabile rispetto alla
     * costruzione di [adjacency] qui sopra. -1 per un nodo senza nessun arco
     * attivo (mai assegnato a nessuna componente, mai scelto da uno snap).
     * Usata da [snapPairToGraph] per non snappare piu' su un frammento
     * isolato che non porta da nessuna parte (Alessandro, 2026-10-02: con
     * OSM acceso, uno snap piu' vicino ma su un troncone staccato toglieva
     * un percorso che con la sola sorgente precedente funzionava) restando
     * pero' instradabile quando partenza e arrivo stanno davvero sullo
     * stesso ramo isolato (in quel caso e' la loro unica componente in
     * comune, e viene accettata). */
    val componentId: IntArray by lazy {
        val comp = IntArray(graph.nodes.size) { -1 }
        var next = 0
        for (startNode in graph.nodes.indices) {
            if (comp[startNode] != -1 || adjacency[startNode].isEmpty()) continue
            val stack = ArrayDeque<Int>()
            stack.addLast(startNode)
            comp[startNode] = next
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                for ((_, other) in adjacency[n]) {
                    if (comp[other] == -1) {
                        comp[other] = next
                        stack.addLast(other)
                    }
                }
            }
            next++
        }
        comp
    }
}

/** Where an arbitrary point (GPS fix, geocoded address, map tap) attaches to
 * the routing graph: which edge, which vertex-to-vertex segment of that
 * edge's polyline it falls on, how far along the *whole edge* the snapped
 * point sits (cumulative, in meters - not a fraction local to that one
 * segment, which would be wrong for any edge with more than two vertices),
 * and the actual snapped coordinate. Routing only ever starts and ends on a
 * graph edge, never at the raw input point itself. */
data class GraphSnap(
    val edgeId: Int,
    val segmentIndex: Int,
    val point: LatLon,
    val distanceFromInputM: Double,
    val distanceAlongEdgeM: Double,
)

/**
 * Closest point on any edge of [graph] active in [enabledSources] to
 * [target]; null only if [enabledSources] leaves no edges at all. Brute-force
 * over every active edge's segments - adequate for a graph of a few thousand
 * cycling-infrastructure edges; would need a spatial index for a full street
 * network.
 */
fun snapToGraph(graph: RoutingGraph, target: LatLon, enabledSources: Set<String>): GraphSnap? {
    var best: GraphSnap? = null
    for (edge in graph.edges) {
        if (edge.source !in enabledSources) continue
        var distanceBeforeSegment = 0.0
        for (i in 0 until edge.geom.size - 1) {
            val segA = edge.geom[i]
            val segB = edge.geom[i + 1]
            val segLength = haversineMeters(segA, segB)
            val res = closestPointOnSegment(target, segA, segB)
            val current = best
            if (current == null || res.distanceM < current.distanceFromInputM) {
                best = GraphSnap(
                    edgeId = edge.id,
                    segmentIndex = i,
                    point = res.point,
                    distanceFromInputM = res.distanceM,
                    distanceAlongEdgeM = distanceBeforeSegment + res.fraction * segLength,
                )
            }
            distanceBeforeSegment += segLength
        }
    }
    return best
}

/**
 * Come [snapToGraph] ma per i due estremi di una ricerca insieme: tra tutti
 * i candidati vicini a [start] e a [end], sceglie la coppia piu' vicina
 * (somma delle due distanze) che appartiene alla STESSA componente connessa
 * di [filtered] - vedi il commento su [FilteredGraph.componentId] per il
 * perche'. Null se non esiste nessuna coppia di candidati nella stessa
 * componente (incluso il caso in cui [FilteredGraph.enabledSources] non
 * lascia archi attivi).
 */
fun snapPairToGraph(filtered: FilteredGraph, start: LatLon, end: LatLon, maxCandidatesPerSide: Int = 6): Pair<GraphSnap, GraphSnap>? {
    val startCandidates = topSnapCandidates(filtered.graph, start, filtered.enabledSources, maxCandidatesPerSide)
    val endCandidates = topSnapCandidates(filtered.graph, end, filtered.enabledSources, maxCandidatesPerSide)
    if (startCandidates.isEmpty() || endCandidates.isEmpty()) return null

    fun componentOf(snap: GraphSnap): Int = filtered.componentId[filtered.graph.edges[snap.edgeId].a]

    var best: Pair<GraphSnap, GraphSnap>? = null
    var bestCost = Double.MAX_VALUE
    for (s in startCandidates) {
        val sComp = componentOf(s)
        if (sComp == -1) continue
        for (e in endCandidates) {
            if (componentOf(e) != sComp) continue
            val cost = s.distanceFromInputM + e.distanceFromInputM
            if (cost < bestCost) {
                bestCost = cost
                best = s to e
            }
        }
    }
    return best
}

/** Come la scansione dentro [snapToGraph], ma tiene i migliori [limit]
 * candidati (un arco al massimo ciascuno) invece del solo migliore in
 * assoluto - serve a [snapPairToGraph] per poter scartare un candidato la
 * cui componente non porta all'altro capo e provare il successivo. */
private fun topSnapCandidates(graph: RoutingGraph, target: LatLon, enabledSources: Set<String>, limit: Int): List<GraphSnap> {
    data class Scored(val snap: GraphSnap, val distanceM: Double)
    val found = ArrayList<Scored>()
    for (edge in graph.edges) {
        if (edge.source !in enabledSources) continue
        var distanceBeforeSegment = 0.0
        var bestForThisEdge: GraphSnap? = null
        for (i in 0 until edge.geom.size - 1) {
            val segA = edge.geom[i]
            val segB = edge.geom[i + 1]
            val segLength = haversineMeters(segA, segB)
            val res = closestPointOnSegment(target, segA, segB)
            val current = bestForThisEdge
            if (current == null || res.distanceM < current.distanceFromInputM) {
                bestForThisEdge = GraphSnap(
                    edgeId = edge.id,
                    segmentIndex = i,
                    point = res.point,
                    distanceFromInputM = res.distanceM,
                    distanceAlongEdgeM = distanceBeforeSegment + res.fraction * segLength,
                )
            }
            distanceBeforeSegment += segLength
        }
        bestForThisEdge?.let { found.add(Scored(it, it.distanceFromInputM)) }
    }
    return found.sortedBy { it.distanceM }.take(limit).map { it.snap }
}
