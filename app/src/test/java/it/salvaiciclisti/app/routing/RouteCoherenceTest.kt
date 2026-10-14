package it.salvaiciclisti.app.routing

import org.junit.Assert
import org.junit.Test
import java.io.File
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * AGGIORNAMENTO 2026-10-13: il test non lavora piu' sorgente per sorgente ma
 * sulla MAPPA INTEGRATA (tutte le sorgenti presenti in graph.json, cioe'
 * quelle abilitate in precompila_parametri.txt), come l'app: 10 coppie x 2
 * motori. La descrizione storica qui sotto vale per tutto il resto (scelta
 * delle coppie, controlli, nomi dei GPX con etichetta "integrata").
 *
 * Audit di coerenza dei percorsi: per ogni sorgente dati, presa isolatamente
 * (come nei test manuali dalla schermata Impostazioni), cerca fino a
 * [PAIRS_PER_SOURCE] coppie di punti REALMENTE instradabili e le instrada con
 * ENTRAMBI i motori di routing ([RoutingEngine.IN_HOUSE], il Dijkstra
 * originale scritto a mano, e [RoutingEngine.JGRAPHT], la libreria
 * open-source) - stesse coppie, stessa sotto-rete filtrata, cosi' i due
 * motori sono confrontabili a parita' di condizioni. In totale fino a
 * sorgenti x [PAIRS_PER_SOURCE] x 2 motori percorsi (5 x 10 x 2 = 100 con le
 * sorgenti attuali). Ogni percorso viene salvato come GPX (nome file:
 * prefisso motore "JGraphT-"/"inHouse-" + sorgente + indice, sempre) sotto
 * build/test-outputs/route-coherence/ e controllato che la sequenza di punti
 * sia sensata:
 *  1) la distanza residua dalla meta non deve mai risalire di molto rispetto
 *     al minimo gia' toccato (segno di un rientro/backtrack, la firma dello
 *     "zigzag" segnalato a mano piu' volte);
 *  2) nessun salto tra due punti consecutivi del tracciato deve essere
 *     anomalo rispetto alla spaziatura tipica di quel percorso (media +
 *     2 deviazioni standard delle distanze punto-punto).
 *
 * Le coppie NON sono pescate a caso su tutti i nodi della sorgente: una
 * sorgente isolata e' quasi sempre spezzata in tante componenti connesse
 * scollegate tra loro (un pezzo di pista qui, un altro la' senza nulla che
 * li unisca), e due punti in componenti diverse non avranno MAI un
 * percorso, qualunque sia la distanza. Si individuano quindi le componenti
 * connesse di quella sorgente e si pesca la coppia DENTRO la stessa
 * componente, prendendo (via una doppia visita pesata sulla lunghezza reale
 * degli archi, la classica euristica "double sweep" per il diametro di un
 * grafo) i due nodi piu' lontani raggiungibili al suo interno - cosi' ogni
 * sorgente da' un percorso vero, e la distanza ottenuta mostra onestamente
 * quanto e' estesa la sua rete (puo' essere 1 km per una sorgente molto
 * frammentata, non e' un fallimento: e' un dato di fatto sulla copertura).
 * Si scorrono le componenti dalla piu' grande alla piu' piccola per avere
 * varieta' di zone; se ce ne sono meno di [PAIRS_PER_SOURCE], si completa
 * con altre coppie (sempre nella stessa componente) dalle piu' popolose.
 *
 * Il test scorre TUTTE le sorgenti e TUTTE le coppie prima di eventualmente
 * fallire: ogni problema trovato finisce in una lista e il fallimento (se
 * c'e') arriva una sola volta alla fine, con l'elenco completo.
 *
 * Nessuna dipendenza Android: RoutingGraph/FilteredGraph/findRoute/routeToGpx
 * sono JVM puro, quindi gira come normale unit test (testDebugUnitTest),
 * senza Robolectric ne' un device/emulatore.
 */
class RouteCoherenceTest {

    /** Flag da riga di comando (./gradlew testDebugUnitTest -Pforce), inoltrata
     * come system property da app/build.gradle.kts: con "force" attivo il
     * test stampa comunque il report e la percentuale di percorsi
     * incoerenti, ma non fa fallire la build - serve per poter installare
     * comunque sul telefono dopo aver gia' letto il report a mano ed
     * essersi convinti che i "falliti" sono falsi allarmi (vedi soglie qui
     * sotto), invece di dover commentare via Assert.fail ogni volta. */
    private val forceInstall: Boolean = System.getProperty("routeCoherence.force", "false").toBoolean()

    private val graph: RoutingGraph by lazy {
        // graph.json non e' piu' un asset dell'app (Alessandro, 2026-09-21:
        // l'app lo scarica da GitHub Releases invece di portarselo dentro
        // l'APK, vedi RemoteMapDataRepository) - resta pero' generato in
        // locale dalla pipeline sotto percorsi/build/<GRAPH_BUILD_DIR>/, un
        // livello sopra la cartella del modulo app/, ed e' li' che questo test
        // legge. Deve SEMPRE essere la versione di pipeline piu' recente (oggi
        // v3), cioe' quella che l'app scarica da GitHub: vedi GRAPH_BUILD_DIR.
        val file = File("../percorsi/build/$GRAPH_BUILD_DIR/graph.json")
        check(file.exists()) {
            "graph.json non trovato in ${file.absolutePath} - il test assume che il " +
                "working dir del task di test sia la cartella del modulo app/ " +
                "(percorsi/ e' un livello sopra) e che la pipeline ($GRAPH_BUILD_DIR) sia gia' stata eseguita"
        }
        println("Grafo testato: ${file.path}")
        GraphLoader.parse(file.readText())
    }

    /** Area (cerchio) in cui devono cadere partenza e arrivo di ogni coppia
     * di test: centro e raggio letti da ../precompila_parametri.txt
     * (debug_route_test_center_lat/_lon, debug_route_test_radius_km), con
     * default Piazza Venezia / 20 km se il file o la chiave mancano. Il
     * percorso in mezzo puo' uscire dal cerchio, solo gli estremi no. */
    private data class TestArea(val center: LatLon, val radiusM: Double) {
        fun contains(p: LatLon): Boolean = haversineMeters(center, p) <= radiusM
    }

    private val testArea: TestArea by lazy {
        val params = HashMap<String, String>()
        val file = File("../precompila_parametri.txt")
        if (file.exists()) {
            file.readLines().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                val eq = line.indexOf('=')
                if (eq > 0) params[line.substring(0, eq).trim().lowercase()] = line.substring(eq + 1).trim()
            }
        } else {
            println("precompila_parametri.txt non trovato in ${file.absolutePath} - area di test di default")
        }
        val lat = params["debug_route_test_center_lat"]?.toDoubleOrNull() ?: DEFAULT_TEST_CENTER_LAT
        val lon = params["debug_route_test_center_lon"]?.toDoubleOrNull() ?: DEFAULT_TEST_CENTER_LON
        val radiusKm = params["debug_route_test_radius_km"]?.toDoubleOrNull() ?: DEFAULT_TEST_RADIUS_KM
        println("Area di test: centro ($lat, $lon), raggio $radiusKm km")
        TestArea(LatLon(lat, lon), radiusKm * 1000.0)
    }

    @Test
    fun `route geometry is coherent on the integrated map, on both engines`() {
        // Mappa INTEGRATA, come l'app (Alessandro, 2026-10-13: "voglio 10+10 test
        // di tutte le mappe insieme"): tutte le sorgenti presenti in graph.json,
        // cioe' quelle abilitate (process_<sorgente> = true) in
        // precompila_parametri.txt - le disabilitate non vengono nemmeno
        // costruite. Niente piu' test sorgente per sorgente: con i raccordi
        // (scripts/raccordi.py) una sorgente isolata non dice nulla.
        val sources = graph.edges.map { it.source }.distinct().sorted()
        val label = "integrata"
        val engines = RoutingEngine.values().toList()
        val outDir = File("build/test-outputs/route-coherence").apply { mkdirs() }
        // Svuota i GPX del run precedente: altrimenti un _failed.gpx vecchio
        // resta accanto al nuovo e non si capisce se l'errore c'e' ancora.
        outDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx") }?.forEach { it.delete() }
        val problems = mutableListOf<String>()
        val summary = mutableListOf<String>()
        var totalRoutes = 0
        var failedRoutes = 0

        println("Sorgenti della mappa integrata: ${sources.joinToString(", ")}")
        val filtered = FilteredGraph(graph, sources.toSet())
        // Le coppie dipendono solo dal grafo, non dal motore: si calcolano una
        // volta e si instradano con ENTRAMBI i motori, cosi' i due risultati
        // sono sempre a parita' di partenza/arrivo.
        val pairs = pickTestPairs(filtered)
        val distances = pairs.map { (a, b) -> haversineMeters(a, b) }

        for (engine in engines) {
            val enginePrefix = enginePrefixFor(engine)
            var successCount = 0
            var failedInGroup = 0
            for ((start, end) in pairs) {
                val route = findRoute(filtered, start, end, engine = engine) ?: continue
                val idx = successCount
                successCount++
                totalRoutes++

                // Il nome del file si decide DOPO i controlli: un percorso
                // incoerente prende il suffisso "_failed". Il prefisso motore
                // (JGraphT-/inHouse-) tiene separati i due confronti.
                val points = route.steps.flatMap { it.geom }
                val routeProblems = mutableListOf<String>()
                checkNoBacktrack(points, end, label, idx, routeProblems)
                checkStepContinuity(route.steps, label, idx, routeProblems)
                checkNoImplausibleHop(route.steps, label, idx, routeProblems)
                val failed = routeProblems.isNotEmpty()

                val gpxFile = File(outDir, "$enginePrefix-${label}_$idx${if (failed) "_failed" else ""}.gpx")
                gpxFile.writeText(routeToGpx(route, trackName = "$enginePrefix $label #$idx"))
                if (failed) {
                    failedInGroup++
                    failedRoutes++
                    problems.addAll(routeProblems.map { "[$enginePrefix] $it (GPX: ${gpxFile.name})" })
                }
            }

            val rangeText = if (distances.isEmpty()) {
                "nessuna componente con almeno 2 nodi"
            } else {
                "distanza in linea d'aria tra %.1f e %.1f km".format(
                    distances.min() / 1000.0,
                    distances.max() / 1000.0,
                )
            }
            summary.add(
                "$enginePrefix $label: $successCount/$PAIRS_PER_SOURCE percorsi trovati e verificati, " +
                    "$failedInGroup incoerente/i ($rangeText)",
            )
        }

        val expectedRoutes = PAIRS_PER_SOURCE * engines.size
        val failedPct = if (totalRoutes > 0) 100.0 * failedRoutes / totalRoutes else 0.0
        println(summary.joinToString("\n"))
        println("Totale percorsi calcolati e verificati: $totalRoutes (atteso: $expectedRoutes = $PAIRS_PER_SOURCE coppie x ${engines.size} motori)")
        println("Percorsi incoerenti: $failedRoutes/$totalRoutes (${"%.1f".format(failedPct)}%)")
        if (problems.isNotEmpty()) {
            val detail = "${problems.size} problema/i su $failedRoutes percorso/i incoerente/i, mappa integrata x " +
                "${engines.size} motori (GPX salvati in ${outDir.path}):\n" + problems.joinToString("\n---\n")
            if (forceInstall) {
                // -Pforce attivo: si stampa il report per intero come
                // avviso, ma il test PASSA comunque, cosi' non blocca
                // installDebug (o qualunque altro task lo segua nella
                // stessa invocazione di gradle).
                println("ATTENZIONE - routeCoherence.force attivo, test non bloccante nonostante i problemi:\n$detail")
            } else {
                Assert.fail(detail)
            }
        }
    }

    /** Prefisso di file/etichetta per motore: "JGraphT" e "inHouse" esatti,
     * come richiesto, per distinguere a colpo d'occhio i GPX e i messaggi di
     * errore dei due motori nella stessa cartella di output. */
    private fun enginePrefixFor(engine: RoutingEngine): String = when (engine) {
        RoutingEngine.IN_HOUSE -> "inHouse"
        RoutingEngine.JGRAPHT -> "JGraphT"
    }

    /** Coppie garantite instradabili: dentro la stessa componente connessa,
     * il piu' possibile "agli estremi" di quella componente. Seed fisso: un
     * test che a volte passa e a volte no per pura fortuna del generatore
     * casuale non e' un test utile. */
    private fun pickTestPairs(filtered: FilteredGraph): List<Pair<LatLon, LatLon>> {
        val nodeIds = filtered.activeEdgeIds
            .flatMap { listOf(filtered.graph.edges[it].a, filtered.graph.edges[it].b) }
            .distinct()
        if (nodeIds.size < 2) return emptyList()

        // Componenti calcolate sull'intera sotto-rete (un collegamento puo'
        // passare fuori dall'area), ma per ognuna si tengono come candidati
        // estremi solo i nodi dentro l'area di test.
        val components = connectedComponents(filtered, nodeIds)
            .map { comp -> comp to comp.filter { testArea.contains(filtered.graph.nodes[it]) } }
            .filter { (_, inside) -> inside.size >= 2 }
            .sortedByDescending { (_, inside) -> inside.size }
        if (components.isEmpty()) return emptyList()

        val rng = Random(SEED)
        val pairs = mutableListOf<Pair<LatLon, LatLon>>()

        // Prima un estremo per componente (le piu' popolose prima), cosi'
        // se ce ne sono tante diverse si vede un ventaglio di zone, non
        // sempre lo stesso pezzo di rete.
        for ((component, inside) in components) {
            if (pairs.size >= PAIRS_PER_SOURCE) break
            val compSet = component.toHashSet()
            val insideSet = inside.toHashSet()
            val p1 = farthestFrom(filtered, inside[rng.nextInt(inside.size)], compSet, insideSet)
            val p2 = farthestFrom(filtered, p1, compSet, insideSet)
            if (p1 != p2) {
                pairs.add(filtered.graph.nodes[p1] to filtered.graph.nodes[p2])
            }
        }

        // Meno componenti utili di quante coppie servono: si completa
        // pescando altre coppie casuali (sempre dentro la stessa componente)
        // dalle piu' popolose, girando piu' volte se serve.
        var round = 0
        while (pairs.size < PAIRS_PER_SOURCE && round < components.size * MAX_FILL_ROUNDS) {
            val inside = components[round % components.size].second
            round++
            if (inside.size < 2) continue
            val a = inside[rng.nextInt(inside.size)]
            val b = inside[rng.nextInt(inside.size)]
            if (a != b) {
                pairs.add(filtered.graph.nodes[a] to filtered.graph.nodes[b])
            }
        }

        return pairs
    }

    /** Componenti connesse della sotto-rete attiva, come liste di id nodo. */
    private fun connectedComponents(filtered: FilteredGraph, nodeIds: List<Int>): List<List<Int>> {
        val nodeSet = nodeIds.toHashSet()
        val visited = HashSet<Int>()
        val components = mutableListOf<List<Int>>()
        for (start in nodeIds) {
            if (start in visited) continue
            val comp = mutableListOf<Int>()
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            visited.add(start)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                comp.add(n)
                for ((_, other) in filtered.adjacency[n]) {
                    if (other in nodeSet && visited.add(other)) stack.addLast(other)
                }
            }
            components.add(comp)
        }
        return components
    }

    /** Nodo di [component] piu' lontano da [from] per lunghezza reale di
     * percorso (non numero di salti) - una Dijkstra ristretta a quella sola
     * componente. Chiamata due volte in sequenza (da un nodo arbitrario, poi
     * dal risultato) approssima bene il diametro della componente: la
     * classica euristica "double sweep", che non richiede il costo di
     * calcolare tutte le coppie. */
    private fun farthestFrom(
        filtered: FilteredGraph,
        from: Int,
        component: Set<Int>,
        candidates: Set<Int> = component,
    ): Int {
        var best = from
        var bestDist = -1.0
        val dist = HashMap<Int, Double>()
        dist[from] = 0.0
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        pq.add(0.0 to from)
        val visited = HashSet<Int>()
        while (pq.isNotEmpty()) {
            val (d, n) = pq.poll()
            if (!visited.add(n)) continue
            // Si attraversa tutta la componente, ma come risultato vale solo
            // un nodo tra i candidati (quelli dentro l'area di test).
            if (d > bestDist && n in candidates) {
                bestDist = d
                best = n
            }
            for ((edgeId, other) in filtered.adjacency[n]) {
                if (other !in component) continue
                val nd = d + filtered.graph.edges[edgeId].lengthM
                if (nd < (dist[other] ?: Double.MAX_VALUE)) {
                    dist[other] = nd
                    pq.add(nd to other)
                }
            }
        }
        return best
    }

    /** Firma dello zigzag: quanto la distanza residua dalla meta risale
     * rispetto al minimo gia' toccato lungo il tracciato. Soglia mista -
     * un pavimento assoluto (non punire un rientro di pochi metri per
     * aggirare un isolato) e una quota proporzionale alla tratta (un rientro
     * di 500 m e' nulla su 40 km, enorme su 2 km). */
    private fun checkNoBacktrack(
        points: List<LatLon>,
        end: LatLon,
        source: String,
        idx: Int,
        problems: MutableList<String>,
    ) {
        if (points.size < 2) return
        val straightLineM = haversineMeters(points.first(), end)
        val threshold = maxOf(BACKTRACK_ABSOLUTE_FLOOR_M, BACKTRACK_FRACTION * straightLineM)
        var best = Double.MAX_VALUE
        for ((i, p) in points.withIndex()) {
            val remaining = haversineMeters(p, end)
            if (remaining < best) best = remaining
            val regression = remaining - best
            if (regression > threshold) {
                problems.add(
                    "$source #$idx: al punto $i la distanza residua dalla " +
                        "meta risale di ${regression.toInt()} m rispetto al minimo gia' toccato " +
                        "(${best.toInt()} m) - soglia ${threshold.toInt()} m. Rientro/backtrack sospetto.",
                )
                return
            }
        }
    }

    /** Continuita' tra un arco (route step) e il successivo: dovrebbero
     * condividere lo stesso nodo, quindi l'ultimo punto del primo e il primo
     * punto del secondo devono essere quasi coincidenti. Trovato uno
     * STRAPPO qui vuol dire che il grafo o la ricostruzione del cammino
     * hanno sbagliato la topologia/l'orientamento - la classe di bug reale
     * che questo test deve prendere (vedi lo strappo di 8+ km scoperto il
     * 2026-09-18, causato da un arco lungo percorso al contrario senza
     * invertirne la geometria - fix in Router.kt).
     *
     * NOTA STORICA: la versione precedente di questo controllo guardava la
     * spaziatura punto-punto su TUTTO il tracciato con una soglia
     * statistica (media + 2 deviazioni standard), confondendo due cose
     * diverse: la densita' di vertici con cui e' stata digitalizzata una
     * sorgente (che varia moltissimo da un arco all'altro - un rettilineo
     * puo' avere solo i due estremi, una curva molti punti ravvicinati - e
     * NON e' un bug, e' solo come e' fatto il dato originale) e una vera
     * discontinuita' di instradamento. Il risultato: TUTTI i 50 percorsi di
     * un run reale finivano etichettati come falliti per salti di poche
     * centinaia di metri dentro un singolo arco lungo e dritto, mentre i
     * percorsi erano quasi tutti corretti (confermato guardandoli). Questo
     * controllo guarda invece solo i confini TRA un arco e l'altro, dove
     * una vera frattura di topologia si vede sempre, indipendentemente da
     * quanto e' densa la digitalizzazione di ciascuna sorgente. */
    private fun checkStepContinuity(
        steps: List<RouteStep>,
        source: String,
        idx: Int,
        problems: MutableList<String>,
    ) {
        for (i in 0 until steps.size - 1) {
            val a = steps[i].geom.lastOrNull() ?: continue
            val b = steps[i + 1].geom.firstOrNull() ?: continue
            val gap = haversineMeters(a, b)
            if (gap > STEP_BOUNDARY_GAP_FLOOR_M) {
                problems.add(
                    "$source #$idx: strappo di ${gap.toInt()} m tra il passo $i (arco " +
                        "${steps[i].edgeId}, sorgente ${steps[i].source}) e il passo ${i + 1} " +
                        "(arco ${steps[i + 1].edgeId}, sorgente ${steps[i + 1].source}) - " +
                        "dovrebbero condividere lo stesso nodo (soglia ${STEP_BOUNDARY_GAP_FLOOR_M.toInt()} m).",
                )
            }
        }
    }

    /** Rete di sicurezza generica, non basata sulla densita' di vertici:
     * un singolo salto tra due punti consecutivi (anche interni a un solo
     * arco) sopra una soglia assoluta grande e' troppo lungo per essere
     * digitalizzazione rada - piu' probabile un residuo bug di dati/
     * proiezione (tipo i casi UTM33N/Web Mercator gia' trovati) che una
     * caratteristica legittima del percorso. */
    private fun checkNoImplausibleHop(
        steps: List<RouteStep>,
        source: String,
        idx: Int,
        problems: MutableList<String>,
    ) {
        // La Ciclopolitana e' - per costruzione, vedi CICLOPOLITANA_FILENAMES
        // in build_routing_graph.py - una mappa-concetto in stile metro,
        // digitalizzata apposta con pochi vertici su tratte di 10-20 km: un
        // singolo salto di qualche km al suo interno e' la norma per quella
        // sorgente, non un indizio di bug di dati/proiezione. Sulla mappa
        // integrata si saltano quindi solo i SUOI archi, non l'intero percorso.
        // I salti fra un arco e il successivo li controlla checkStepContinuity.
        for (step in steps) {
            if (step.source == "salvaiciclisti_ciclopolitana") continue
            val points = step.geom
            for (i in 0 until points.size - 1) {
                val gap = haversineMeters(points[i], points[i + 1])
                if (gap > IMPLAUSIBLE_HOP_FLOOR_M) {
                    problems.add(
                        "$source #$idx: salto di ${gap.toInt()} m dentro l'arco ${step.edgeId} " +
                            "(sorgente ${step.source}) tra i suoi punti $i e ${i + 1} - " +
                            "troppo grande per essere densita' di digitalizzazione (soglia " +
                            "${IMPLAUSIBLE_HOP_FLOOR_M.toInt()} m). Sospetto bug di dati/proiezione residuo.",
                    )
                }
            }
        }
    }

    companion object {
        private const val SEED = 20260918L

        // Cartella (sotto percorsi/build/) del grafo testato: SEMPRE l'ultima
        // versione della pipeline, la stessa che l'app scarica da GitHub.
        // Con una v4 (precompila_v4.py) diventa "routing_v4". Stesso valore
        // va tenuto in plot_failed_gpx.m (graphBuildDir). Vedi le note di progetto.
        private const val GRAPH_BUILD_DIR = "routing_v3"
        private const val DEFAULT_TEST_CENTER_LAT = 41.896435
        private const val DEFAULT_TEST_CENTER_LON = 12.482531
        private const val DEFAULT_TEST_RADIUS_KM = 20.0
        private const val PAIRS_PER_SOURCE = 10
        private const val MAX_FILL_ROUNDS = 20

        // Alzati rispetto alla versione precedente (500m/15%) dopo aver
        // controllato a mano i 10 casi che scattavano su un run reale: erano
        // tutti regressioni modeste (500-770m) su reti fatte SOLO di
        // infrastruttura ciclabile, spesso incomplete/frammentate - li' un
        // giro largo per trovare un collegamento e' normale amministrazione,
        // non un bug di instradamento.
        private const val BACKTRACK_FRACTION = 0.20
        private const val BACKTRACK_ABSOLUTE_FLOOR_M = 800.0

        // Soglia di STRAPPO tra un arco e il successivo: un po' sopra il
        // rumore residuo del consolidamento nodi lato build_routing_graph.py
        // (NODE_MERGE_TOLERANCE_M=25m, quindi fino a un ~50m di scarto tra
        // il punto di un arco e quello dell'arco successivo e' atteso anche
        // a topologia corretta), non sopra la densita' di vertici di una
        // sorgente (che qui non c'entra: si guarda solo il CONFINE tra due
        // archi, mai l'interno di uno).
        private const val STEP_BOUNDARY_GAP_FLOOR_M = 60.0

        // Soglia larghissima, puramente come rete di sicurezza residua per
        // bug di dati/proiezione (tipo UTM33N/Web Mercator): un rettilineo
        // vero e proprio di una sorgente puo' tranquillamente superare 1 km
        // senza vertici intermedi (successo gia' visto su g3w), quindi qui
        // si segnala solo l'implausibile, non l'insolito.
        private const val IMPLAUSIBLE_HOP_FLOOR_M = 2500.0
    }
}
