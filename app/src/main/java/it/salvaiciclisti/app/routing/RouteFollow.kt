package it.salvaiciclisti.app.routing

/** How far a live GPS fix can stray from the selected route and still count
 * as "on track" - tighter than a generic GPX-track follower would use (GTL
 * uses 50m for its mix of secondary roads/unpaved tracks) since here the
 * route runs on marked cycling infrastructure, where a real deviation
 * should be caught sooner. */
const val ON_ROUTE_THRESHOLD_M = 30.0

data class RouteProfilePoint(val point: LatLon, val cumulativeDistanceM: Double)

/** Flattens every step's geometry into one ordered polyline with cumulative
 * distance from the route's start - the same shape GTL's buildTrackProfile
 * builds from a raw GPX track, built here instead from a computed
 * [RouteResult]'s steps. */
fun buildRouteProfile(route: RouteResult): List<RouteProfilePoint> {
    val profile = mutableListOf<RouteProfilePoint>()
    var cumulative = 0.0
    var previous: LatLon? = null
    for (step in route.steps) {
        for (p in step.geom) {
            val prev = previous
            if (prev != null) {
                val d = haversineMeters(prev, p)
                if (d < 1e-6) continue // skip an exact duplicate point at a step boundary
                cumulative += d
            }
            profile.add(RouteProfilePoint(p, cumulative))
            previous = p
        }
    }
    return profile
}

data class FollowProgress(
    val distanceFromRouteM: Double,
    val isOnTrack: Boolean,
    val remainingDistanceM: Double,
)

/**
 * Where a live position sits relative to the route's profile: how far off
 * it is, and how much distance is left from the nearest point to the end.
 * Null only if the profile has fewer than 2 points (nothing to project
 * onto).
 */
fun computeFollowProgress(profile: List<RouteProfilePoint>, current: LatLon): FollowProgress? {
    if (profile.size < 2) return null

    var bestDistanceM = Double.MAX_VALUE
    var bestIndex = 0
    for (i in 0 until profile.size - 1) {
        val res = closestPointOnSegment(current, profile[i].point, profile[i + 1].point)
        if (res.distanceM < bestDistanceM) {
            bestDistanceM = res.distanceM
            bestIndex = i
        }
    }

    val a = profile[bestIndex]
    val b = profile[bestIndex + 1]
    val nearest = if (haversineMeters(current, a.point) <= haversineMeters(current, b.point)) a else b
    val totalDistanceM = profile.last().cumulativeDistanceM
    val remainingDistanceM = (totalDistanceM - nearest.cumulativeDistanceM).coerceAtLeast(0.0)

    return FollowProgress(
        distanceFromRouteM = bestDistanceM,
        isOnTrack = bestDistanceM <= ON_ROUTE_THRESHOLD_M,
        remainingDistanceM = remainingDistanceM,
    )
}
