package it.salvaiciclisti.app.routing

import org.junit.Assert
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * AGGIORNAMENTO 2026-10-14: partenza e arrivo sono due INDIRIZZI a caso
 * dall'indice indirizzi.json (lo stesso dell'app e di raccordi.py), scartati
 * e ripescati se almeno uno cade fuori dall'area di test. Seme casuale nuovo
 * a ogni esecuzione, stampato; si forza con -PseedPercorsi=N per rifare
 * esattamente le stesse coppie. Una coppia senza percorso conta come
 * fallimento. La descrizione delle coppie "per componente" piu' sotto e'
 * storica.
 *
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

    /** (2026-11-20) Legge un grafo della pipeline da
     * ../percorsi/build/<GRAPH_BUILD_DIR>/<nome> (il working dir del test e'
     * la cartella del modulo app/). Null se il file non c'e'. GRAPH_BUILD_DIR
     * deve SEMPRE essere la versione di pipeline piu' recente (oggi v4, la
     * stessa inclusa nell'APK). */
    private fun loadGraph(name: String): RoutingGraph? {
        val file = File("../percorsi/build/$GRAPH_BUILD_DIR/$name")
        if (!file.exists()) return null
        println("Grafo testato: ${file.path}")
        return GraphLoader.parse(file.readText())
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
        // Mappa INTEGRATA, come l'app (Alessandro, 2026-10-13): tutte le
        // sorgenti presenti nel grafo. (2026-11-20) Due grafi, stesse coppie:
        // "integrata" = graph.json (SIC + raccordi + accessi), "heatmap" =
        // graph_heatmap.json (SIC + strade piu' percorse della heat map), per
        // confrontare le due reti di supporto. Il grafo heat map e' facoltativo:
        // se manca si testa solo graph.json.
        val engines = RoutingEngine.values().toList()
        val outDir = File("build/test-outputs/route-coherence").apply { mkdirs() }
        // Svuota i GPX del run precedente: altrimenti un _failed.gpx vecchio
        // resta accanto al nuovo e non si capisce se l'errore c'e' ancora.
        outDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx") }?.forEach { it.delete() }
        val problems = mutableListOf<String>()
        val summary = mutableListOf<String>()
        var totalRoutes = 0
        var failedRoutes = 0

        val reti = mutableListOf<Pair<String, RoutingGraph>>()
        val integrata = loadGraph("graph.json")
        check(integrata != null) {
            "graph.json non trovato in ${File("../percorsi/build/$GRAPH_BUILD_DIR").absolutePath} - il test assume " +
                "che il working dir del task di test sia la cartella del modulo app/ e che la pipeline " +
                "($GRAPH_BUILD_DIR) sia gia' stata eseguita"
        }
        reti.add("integrata" to integrata)
        val heatmap = loadGraph("graph_heatmap.json")
        if (heatmap != null) {
            reti.add("heatmap" to heatmap)
        } else {
            println("graph_heatmap.json non trovato in ../percorsi/build/$GRAPH_BUILD_DIR: solo graph.json")
        }

        // Le coppie non dipendono ne' dal grafo ne' dal motore: si calcolano
        // una volta e si instradano su ogni grafo con ENTRAMBI i motori. Il
        // numero nel nome del GPX e' l'indice della coppia (stesso numero =
        // stessa coppia su tutti i grafi e motori; un numero manca se quella
        // coppia non ha percorso).
        val pairs = pickTestPairs()
        val distances = pairs.map { (a, b) -> haversineMeters(a, b) }

        for ((label, graph) in reti) {
            val sources = graph.edges.map { it.source }.distinct().sorted()
            println("Sorgenti della mappa $label: ${sources.joinToString(", ")}")
            val filtered = FilteredGraph(graph, sources.toSet())

            for (engine in engines) {
                val enginePrefix = enginePrefixFor(engine)
                var successCount = 0
                var failedInGroup = 0
                for ((pairIdx, pair) in pairs.withIndex()) {
                    val (start, end, startName, endName) = pair
                    // (2026-10-14) Lo stesso calcolo dell'app: il percorso n.1 della
                    // nuova impostazione (costo pesato SIC/raccordi/salita, con le
                    // due valvole), coi parametri di default.
                    val route = findAppRoutes(
                        filtered, start, end, AppRoutingParams(engine = engine, numeroPercorsi = 1),
                    ).firstOrNull()?.result
                    if (route == null) {
                        // Con i raccordi ogni indirizzo dovrebbe essere collegato:
                        // un percorso mancante e' un difetto, si segnala.
                        totalRoutes++
                        failedInGroup++
                        failedRoutes++
                        problems.add(
                            "[$enginePrefix] $label: nessun percorso da $startName (${start.lat}, ${start.lon}) " +
                                "a $endName (${end.lat}, ${end.lon}) (seme $seed)",
                        )
                        continue
                    }
                    val idx = pairIdx
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
                    gpxFile.writeText(
                        routeToGpx(route, trackName = "$enginePrefix $label #$idx", description = "$startName -> $endName"),
                    )
                    if (failed) {
                        failedInGroup++
                        failedRoutes++
                        problems.addAll(routeProblems.map { "[$enginePrefix] $it (GPX: ${gpxFile.name})" })
                    }
                }

                val rangeText = if (distances.isEmpty()) {
                    "nessun indirizzo nell'area di test"
                } else {
                    "distanza in linea d'aria tra %.1f e %.1f km".format(
                        distances.min() / 1000.0,
                        distances.max() / 1000.0,
                    )
                }
                summary.add(
                    "$enginePrefix $label: $successCount/${pairs.size} percorsi trovati e verificati, " +
                        "$failedInGroup incoerente/i ($rangeText)",
                )
            }
        }

        val expectedRoutes = pairs.size * engines.size * reti.size
        val failedPct = if (totalRoutes > 0) 100.0 * failedRoutes / totalRoutes else 0.0
        println(summary.joinToString("\n"))
        println(
            "Totale percorsi calcolati e verificati: $totalRoutes (atteso: $expectedRoutes = ${pairs.size} coppie x " +
                "${engines.size} motori x ${reti.size} grafi)",
        )
        println("Percorsi incoerenti: $failedRoutes/$totalRoutes (${"%.1f".format(failedPct)}%)")
        if (problems.isNotEmpty()) {
            val detail = "${problems.size} problema/i su $failedRoutes percorso/i incoerente/i, " +
                "${reti.joinToString("+") { it.first }} x ${engines.size} motori (GPX salvati in ${outDir.path}):\n" + problems.joinToString("\n---\n")
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

    /** Seme del generatore casuale: nuovo a ogni esecuzione, oppure quello
     * passato con ./gradlew ... -PseedPercorsi=N (system property
     * routeCoherence.seed) per rifare le stesse coppie. */
    private val seed: Long by lazy {
        val forced = System.getProperty("routeCoherence.seed", "").trim().toLongOrNull()
        val s = forced ?: System.nanoTime()
        println("Seme delle coppie di prova: $s" + if (forced != null) " (forzato)" else " (per rifarle: -PseedPercorsi=$s)")
        s
    }

    /** Indirizzi (punti rappresentativi di ogni via) da indirizzi.json: prima
     * quello della versione di pipeline testata (GRAPH_BUILD_DIR, lo stesso
     * che l'app scarica), altrimenti quello in percorsi/build/routing/ (dove
     * lo scrive build_index_indirizzi_v3.py e lo legge raccordi.py). Formato
     * {"strade": {"nome": [[lat, lon], ...], ...}}, letto con una regex per
     * non dipendere da una libreria JSON nello unit test. */
    private fun loadAddresses(): List<Pair<String, List<LatLon>>> {
        val file = listOf(
            File("../percorsi/build/$GRAPH_BUILD_DIR/indirizzi.json"),
            File("../percorsi/build/routing/indirizzi.json"),
        ).firstOrNull { it.exists() }
        check(file != null) { "indirizzi.json non trovato in ../percorsi/build/$GRAPH_BUILD_DIR/ ne' in ../percorsi/build/routing/" }
        println("Indirizzi: ${file.path}")
        val text = file.readText()
        val streetRe = Regex("""\"((?:[^"\\]|\\.)*)"\s*:\s*\[((?:\s*\[[^\]]*\]\s*,?)*)\s*\]""")
        val pointRe = Regex("""\[\s*(-?[0-9.eE+-]+)\s*,\s*(-?[0-9.eE+-]+)\s*\]""")
        return streetRe.findAll(text).map { m ->
            val name = m.groupValues[1].replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
            name to pointRe.findAll(m.groupValues[2]).map { pm ->
                LatLon(pm.groupValues[1].toDouble(), pm.groupValues[2].toDouble())
            }.toList()
        }.filter { it.second.isNotEmpty() }.toList()
    }

    /** Una coppia di prova: punti e nomi delle vie di partenza e arrivo. */
    private data class TestPair(val start: LatLon, val end: LatLon, val startName: String, val endName: String)

    /** Coppie di prova: due indirizzi a caso (via a caso, poi uno dei suoi
     * punti a caso), di vie diverse; la coppia si scarta e si ripesca se
     * almeno uno dei due cade fuori dall'area di test. */
    private fun pickTestPairs(): List<TestPair> {
        val all = loadAddresses()
        val inArea = all.count { (_, pts) -> pts.any { testArea.contains(it) } }
        println("Vie nell'indice: ${all.size}, con almeno un indirizzo nell'area di test: $inArea")
        if (inArea < 2) return emptyList()
        val maxDistM = System.getProperty("routeCoherence.maxDistKm", "").trim().toDoubleOrNull()
            ?.takeIf { it > 0 }?.times(1000.0)
        if (maxDistM != null) println("Distanza massima fra partenza e arrivo: ${maxDistM / 1000.0} km (-PmaxDist)")
        val rng = Random(seed)
        val pairs = mutableListOf<TestPair>()
        var attempts = 0
        while (pairs.size < PAIRS_PER_SOURCE && attempts < PAIRS_PER_SOURCE * MAX_ATTEMPTS_PER_PAIR) {
            attempts++
            val i = rng.nextInt(all.size)
            val j = rng.nextInt(all.size)
            if (i == j) continue
            val (nameA, ptsA) = all[i]
            val (nameB, ptsB) = all[j]
            val a = ptsA[rng.nextInt(ptsA.size)]
            val b = ptsB[rng.nextInt(ptsB.size)]
            if (!testArea.contains(a) || !testArea.contains(b)) continue
            if (maxDistM != null && haversineMeters(a, b) > maxDistM) continue
            pairs.add(TestPair(a, b, nameA, nameB))
        }
        return pairs
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
        // Cartella (sotto percorsi/build/) del grafo testato: SEMPRE l'ultima
        // versione della pipeline, la stessa che l'app scarica da GitHub.
        // (2026-11-20: v4, la stessa inclusa nell'APK.) Stesso valore va tenuto in
        // plot_failed_gpx.m e plot_all_gpx.m (graphBuildDir). Vedi le note di progetto.
        private const val GRAPH_BUILD_DIR = "routing_v4"
        private const val DEFAULT_TEST_CENTER_LAT = 41.896435
        private const val DEFAULT_TEST_CENTER_LON = 12.482531
        private const val DEFAULT_TEST_RADIUS_KM = 20.0
        // Numero di coppie partenza/arrivo: da riga di comando con
        // ./gradlew testDebugUnitTest -PnumPercorsi=N (default 10), inoltrato
        // come system property da app/build.gradle.kts (2026-10-14).
        private val PAIRS_PER_SOURCE: Int =
            System.getProperty("routeCoherence.pairs", "10").toIntOrNull()?.coerceAtLeast(1) ?: 10
        // Tentativi massimi (per coppia richiesta) di pescare due indirizzi
        // entrambi dentro l'area di test.
        private const val MAX_ATTEMPTS_PER_PAIR = 1000

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
