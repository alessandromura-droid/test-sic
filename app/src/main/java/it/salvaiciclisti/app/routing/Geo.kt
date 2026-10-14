package it.salvaiciclisti.app.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance between two points, in meters. */
fun haversineMeters(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(h), sqrt(1 - h))
    return EARTH_RADIUS_M * c
}

/** Meters per degree of latitude/longitude at a given latitude - standard
 * WGS84 approximation, accurate enough to turn a short segment into local
 * flat-plane coordinates for [closestPointOnSegment]. */
private fun metersPerDegree(latDeg: Double): Pair<Double, Double> {
    val lat = Math.toRadians(latDeg)
    val mLat = 111_132.92 - 559.82 * cos(2 * lat) + 1.175 * cos(4 * lat)
    val mLon = 111_412.84 * cos(lat) - 93.5 * cos(3 * lat)
    return mLat to mLon
}

data class ClosestPointResult(val point: LatLon, val distanceM: Double, val fraction: Double)

/** Closest point on segment [a, b] to [p], as a local flat-plane projection -
 * a real simplification but a harmless one at the scale of a single graph
 * edge (rarely more than a few hundred meters). [fraction] is how far along
 * a->b the closest point sits (0 = at a, 1 = at b). */
fun closestPointOnSegment(p: LatLon, a: LatLon, b: LatLon): ClosestPointResult {
    val (mLat, mLon) = metersPerDegree(a.lat)
    val px = (p.lon - a.lon) * mLon
    val py = (p.lat - a.lat) * mLat
    val bx = (b.lon - a.lon) * mLon
    val by = (b.lat - a.lat) * mLat
    val segLenSq = bx * bx + by * by
    val t = if (segLenSq > 0) ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0) else 0.0
    val dx = px - t * bx
    val dy = py - t * by
    val distanceM = sqrt(dx * dx + dy * dy)
    val point = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    return ClosestPointResult(point, distanceM, t)
}

/** Compass bearing from [a] to [b], in degrees [0, 360). */
fun bearing(a: LatLon, b: LatLon): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}
