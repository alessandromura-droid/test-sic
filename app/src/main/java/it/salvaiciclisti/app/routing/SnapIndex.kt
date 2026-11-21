package it.salvaiciclisti.app.routing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Lato di una cella della griglia (m). */
private const val CELL_M = 200.0

/** Margine sul limite di distanza garantito dalla griglia: la griglia usa una
 * proiezione equirettangolare a latitudine fissa, [closestPointOnSegment] una
 * proiezione locale per segmento; fra le due la scala differisce di qualche
 * per mille nell'area di Roma. 0.9 copre abbondantemente lo scarto. */
private const val SAFETY = 0.9

private const val M_PER_DEG_LAT = 111_132.0

private class IntBuf {
    var a = IntArray(4)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

/**
 * (2026-11-20) Indice spaziale a griglia per lo snap (vedi [snapPairToGraph]):
 * costruito una volta per [FilteredGraph] (solo archi attivi), poi ogni snap
 * guarda la cella del punto e gli anelli di celle intorno, invece di scorrere
 * tutti i segmenti di tutti gli archi.
 *
 * Risultato identico alla scansione completa di prima: per ogni arco il punto
 * piu' vicino, poi i [limit] archi piu' vicini. Dopo aver visitato gli anelli
 * fino a r, ogni segmento non ancora visto dista almeno r x [CELL_M] (con
 * margine [SAFETY]); la ricerca si ferma solo quando il limit-esimo candidato
 * e' entro quella distanza, altrimenti allarga l'anello (anche oltre le 8
 * celle vicine: un punto lontano dalla rete trova comunque i suoi candidati).
 * Un segmento lungo e' registrato in tutte le celle del suo riquadro.
 */
internal class SnapIndex(private val graph: RoutingGraph, activeEdgeIds: List<Int>) {
    private val mPerDegLon: Double
    private val segEdge: IntArray
    private val segIndex: IntArray
    private val segStart: DoubleArray
    private val segLength: DoubleArray
    private val cells: HashMap<Long, IntArray>
    private var minCx = Int.MAX_VALUE
    private var maxCx = Int.MIN_VALUE
    private var minCy = Int.MAX_VALUE
    private var maxCy = Int.MIN_VALUE

    init {
        val refLat = if (graph.nodes.isEmpty()) 41.9 else graph.nodes.sumOf { it.lat } / graph.nodes.size
        mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))

        var n = 0
        for (ei in activeEdgeIds) n += max(0, graph.edges[ei].geom.size - 1)
        segEdge = IntArray(n)
        segIndex = IntArray(n)
        segStart = DoubleArray(n)
        segLength = DoubleArray(n)

        val bufs = HashMap<Long, IntBuf>()
        var s = 0
        for (ei in activeEdgeIds) {
            val geom = graph.edges[ei].geom
            var along = 0.0
            for (i in 0 until geom.size - 1) {
                val a = geom[i]
                val b = geom[i + 1]
                val len = haversineMeters(a, b)
                segEdge[s] = ei
                segIndex[s] = i
                segStart[s] = along
                segLength[s] = len
                val cx0 = cellX(min(a.lon, b.lon))
                val cx1 = cellX(max(a.lon, b.lon))
                val cy0 = cellY(min(a.lat, b.lat))
                val cy1 = cellY(max(a.lat, b.lat))
                for (cx in cx0..cx1) for (cy in cy0..cy1) {
                    bufs.getOrPut(key(cx, cy)) { IntBuf() }.add(s)
                }
                if (cx0 < minCx) minCx = cx0
                if (cx1 > maxCx) maxCx = cx1
                if (cy0 < minCy) minCy = cy0
                if (cy1 > maxCy) maxCy = cy1
                along += len
                s++
            }
        }
        cells = HashMap(bufs.size * 2)
        for ((k, v) in bufs) cells[k] = v.toArray()
    }

    private fun cellX(lon: Double): Int = floor(lon * mPerDegLon / CELL_M).toInt()
    private fun cellY(lat: Double): Int = floor(lat * M_PER_DEG_LAT / CELL_M).toInt()
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    /** I [limit] archi attivi piu' vicini a [target] (uno snap per arco, il
     * punto piu' vicino su quell'arco), ordinati per distanza. */
    fun nearestEdges(target: LatLon, limit: Int): List<GraphSnap> {
        if (segEdge.isEmpty() || limit <= 0) return emptyList()
        val tx = cellX(target.lon)
        val ty = cellY(target.lat)
        val best = HashMap<Int, GraphSnap>()

        fun visit(s: Int) {
            val e = segEdge[s]
            val i = segIndex[s]
            val geom = graph.edges[e].geom
            val res = closestPointOnSegment(target, geom[i], geom[i + 1])
            val cur = best[e]
            if (cur == null || res.distanceM < cur.distanceFromInputM) {
                best[e] = GraphSnap(
                    edgeId = e,
                    segmentIndex = i,
                    point = res.point,
                    distanceFromInputM = res.distanceM,
                    distanceAlongEdgeM = segStart[s] + res.fraction * segLength[s],
                )
            }
        }

        fun scanCell(cx: Int, cy: Int) {
            if (cx < minCx || cx > maxCx || cy < minCy || cy > maxCy) return
            val list = cells[key(cx, cy)] ?: return
            for (s in list) visit(s)
        }

        // Anelli che non toccano la griglia: saltati.
        val r0 = max(max(minCx - tx, tx - maxCx), max(minCy - ty, ty - maxCy)).coerceAtLeast(0)
        val rMax = max(max(abs(tx - minCx), abs(tx - maxCx)), max(abs(ty - minCy), abs(ty - maxCy)))
        var r = r0
        while (true) {
            if (r == 0) {
                scanCell(tx, ty)
            } else {
                for (cx in max(tx - r, minCx)..min(tx + r, maxCx)) {
                    scanCell(cx, ty - r)
                    scanCell(cx, ty + r)
                }
                for (cy in max(ty - r + 1, minCy)..min(ty + r - 1, maxCy)) {
                    scanCell(tx - r, cy)
                    scanCell(tx + r, cy)
                }
            }
            if (r >= rMax) break
            if (best.size >= limit) {
                val kth = best.values.map { it.distanceFromInputM }.sorted()[limit - 1]
                if (kth <= r * CELL_M * SAFETY) break
            }
            r++
        }
        return best.values.sortedBy { it.distanceFromInputM }.take(limit)
    }
}
