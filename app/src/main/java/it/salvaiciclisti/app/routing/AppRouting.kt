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
    /** (2026-11-21) Raggio (km) dei cerchi attorno a partenza e arrivo in cui
     * si taglia il primo/ultimo miglio, vedi [tagliaPrimoUltimoMiglio];
     * 0 = nessun taglio. */
    val raggioPrimoUltimoMiglioKm: Double = 1.0,
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
    snaps: Pair<GraphSnap, GraphSnap>,
    p: AppRoutingParams,
    penalty: Map<Int, Double>,
): RouteResult? {
    // (2026-11-20) snap calcolato una volta sola in findAppRoutes
    val (startSnap, endSnap) = snaps
    // Partenza e arrivo sullo stesso arco: nessuna scelta da pesare.
    if (startSnap.edgeId == endSnap.edgeId) {
        return findRoute(filtered, start, end, p.engine, snaps = snaps)
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
    // (2026-11-20) Un solo snap per richiesta, passato al percorso di
    // riferimento e a tutti i candidati (prima: fino a 1 + n + 6 snap).
    val snaps = snapPairToGraph(filtered, start, end) ?: return emptyList()
    val baseline = findRoute(filtered, start, end, p.engine, snaps = snaps) ?: return emptyList()
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
        val candidate = findWeightedAppRoute(filtered, start, end, snaps, p, penalty) ?: break
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

/** (2026-11-21) Costo pesato di un percorso secondo [p], senza penalita':
 * stessa formula del costo usato nella ricerca (lunghezza x peso km x peso
 * della sorgente + peso salita x salita nel verso di percorrenza). */
fun costoPesato(route: RouteResult, p: AppRoutingParams): Double =
    route.steps.sumOf { p.pesoKm * it.lengthM * sourceWeightOf(it.source, p) + p.pesoSalita * climbOf(it.ele) }

/**
 * (2026-11-21, Alessandro) Unisce i percorsi calcolati su piu' reti di
 * supporto ([perRete]: un risultato di [findAppRoutes] per rete, ciascuno con
 * fino a N percorsi).
 *
 * Ogni percorso viene prima tagliato col primo/ultimo miglio
 * ([tagliaPrimoUltimoMiglio], raggio [AppRoutingParams.raggioPrimoUltimoMiglioKm]
 * attorno a [start] e [end]); i doppioni nati dal taglio (stessa rete) si
 * scartano. L'ordine NON cambia per il taglio: il costo pesato usato per
 * ordinare e' quello del percorso intero, prima del taglio (Alessandro:
 * l'ordine non puo' cambiare molto); lunghezze e km mostrati sono quelli del
 * solo tratto tagliato (quello disegnato).
 *
 * Con una rete sola: l'ordine di [findAppRoutes]. Con piu' reti: i primi
 * posti vanno al migliore (costo pesato minimo) di ciascuna rete, in ordine
 * di costo, cosi' i primi due sono di reti diverse; poi tutti gli altri in
 * ordine di costo pesato; in tutto al massimo N, rietichettati 1..N.
 * Doppioni fra reti diverse non scartati (gli id degli archi sono diversi
 * nei due grafi): se due reti danno lo stesso tracciato, si vedono
 * sovrapposti.
 */
fun unisciPercorsiReti(
    perRete: List<List<LabeledRoute>>,
    p: AppRoutingParams,
    start: LatLon,
    end: LatLon,
): List<LabeledRoute> {
    val raggioM = p.raggioPrimoUltimoMiglioKm * 1000.0
    val liste = perRete.map { l ->
        val visti = HashSet<List<Any>>()
        l.map { it.result to costoPesato(it.result, p) }
            .map { (r, c) -> tagliaPrimoUltimoMiglio(r, start, end, raggioM) to c }
            .filter { (r, _) -> visti.add(firmaPercorso(r)) }
    }.filter { it.isNotEmpty() }
    if (liste.isEmpty()) return emptyList()
    if (liste.size == 1) return liste[0].mapIndexed { i, (r, _) -> LabeledRoute(i + 1, RouteRule.PESATO, r) }
    val n = p.numeroPercorsi.coerceAtLeast(1)
    val ordinate = liste.map { l -> l.sortedBy { it.second } }
    val primi = ordinate.map { it.first() }.sortedBy { it.second }
    val altri = ordinate.flatMap { it.drop(1) }.sortedBy { it.second }
    return (primi + altri).take(n).mapIndexed { i, (r, _) -> LabeledRoute(i + 1, RouteRule.PESATO, r) }
}

/** Identita' di un percorso per scartare i doppioni dopo il taglio: archi
 * percorsi e primo/ultimo punto (arrotondati a ~1 m). */
private fun firmaPercorso(r: RouteResult): List<Any> {
    val primo = r.steps.firstOrNull()?.geom?.firstOrNull()
    val ultimo = r.steps.lastOrNull()?.geom?.lastOrNull()
    fun k(q: LatLon?) = q?.let { Math.round(it.lat * 1e5) to Math.round(it.lon * 1e5) }
    return listOf(r.steps.map { it.edgeId }, k(primo) ?: 0, k(ultimo) ?: 0)
}

/** Soglia del taglio: L(P) > RAPPORTO_TAGLIO x d(P), fissa (Alessandro, 2026-11-21). */
private const val RAPPORTO_TAGLIO = 1.5

/**
 * (2026-11-21, Alessandro) Taglio del primo e dell'ultimo miglio. Filosofia:
 * il primo e l'ultimo miglio te li studi da solo; l'app ottimizza l'uso delle
 * grandi ciclabili. Fatto DOPO il calcolo, sul percorso gia' trovato.
 *
 * Arrivo: per ogni vertice P del percorso entro [raggioM] da [end] (in linea
 * d'aria), L(P) = lunghezza del percorso da P alla fine, d(P) = distanza in
 * linea d'aria da P a [end]; si taglia al PRIMO P (dall'inizio) con
 * L(P) > 1.5 x d(P): il percorso finisce in P. Partenza: a specchio, con
 * L(P) = lunghezza dall'inizio a P e d(P) da [start], cercando dalla fine
 * all'indietro: il percorso comincia in P. Se il percorso punta verso il
 * centro L ~ d e non si taglia; se ci gira attorno si taglia all'inizio del
 * giro. Il tratto tolto (il "volo" fino a partenza/arrivo) non si disegna
 * mai. Giri legittimi (ponti, sensi unici) vengono tagliati anch'essi:
 * accettato. Se i due tagli si sovrappongono (partenza e arrivo vicini) o
 * resterebbe meno di un segmento, il percorso resta intero. Taglio sui
 * vertici, senza interpolare dentro un segmento.
 */
fun tagliaPrimoUltimoMiglio(route: RouteResult, start: LatLon, end: LatLon, raggioM: Double): RouteResult {
    if (raggioM <= 0.0 || route.steps.isEmpty()) return route
    // vertici del percorso: (indice step, indice punto), senza ripetere il
    // punto di giunzione fra uno step e il successivo
    val pos = ArrayList<Pair<Int, Int>>()
    val pts = ArrayList<LatLon>()
    route.steps.forEachIndexed { si, st ->
        st.geom.forEachIndexed { pi, q ->
            if (pi == 0 && pts.isNotEmpty() && pts.last() == q) return@forEachIndexed
            pos.add(si to pi)
            pts.add(q)
        }
    }
    if (pts.size < 2) return route
    val cum = DoubleArray(pts.size)
    for (k in 1 until pts.size) cum[k] = cum[k - 1] + haversineMeters(pts[k - 1], pts[k])
    val tot = cum.last()

    var iE = pts.size - 1
    for (k in pts.indices) {
        val d = haversineMeters(pts[k], end)
        if (d <= raggioM && tot - cum[k] > RAPPORTO_TAGLIO * d) { iE = k; break }
    }
    var iS = 0
    for (k in pts.indices.reversed()) {
        val d = haversineMeters(pts[k], start)
        if (d <= raggioM && cum[k] > RAPPORTO_TAGLIO * d) { iS = k; break }
    }
    if (iS == 0 && iE == pts.size - 1) return route
    if (iE - iS < 1) return route

    val (sS, pS) = pos[iS]
    val (sE, pE) = pos[iE]
    val steps = ArrayList<RouteStep>()
    for (si in sS..sE) {
        val st = route.steps[si]
        val da = if (si == sS) pS else 0
        val a = if (si == sE) pE else st.geom.size - 1
        if (a - da < 1) continue
        if (da == 0 && a == st.geom.size - 1) { steps.add(st); continue }
        val geom = st.geom.subList(da, a + 1).toList()
        val intero = lunghezzaPolilinea(st.geom)
        val parte = lunghezzaPolilinea(geom)
        val len = if (intero > 0.0) st.lengthM * parte / intero else parte
        steps.add(st.copy(lengthM = len, geom = geom, ele = st.ele?.let { e -> if (e.size == st.geom.size) e.subList(da, a + 1).toList() else e }))
    }
    if (steps.isEmpty()) return route
    return route.copy(steps = steps, totalLengthM = steps.sumOf { it.lengthM })
}

private fun lunghezzaPolilinea(g: List<LatLon>): Double {
    var t = 0.0
    for (k in 1 until g.size) t += haversineMeters(g[k - 1], g[k])
    return t
}
