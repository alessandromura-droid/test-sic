package it.salvaiciclisti.app.location

/**
 * Smooths a live GPS-reported bearing (course, degrees clockwise from
 * north) into something stable enough to show/rotate by - consecutive raw
 * fixes jump around too much to use directly. Exponential smoothing around
 * the circle: the step from [previous] to [target] is taken the short way
 * via [shortestAngleDelta], so a fix that jumps from 359 deg to 1 deg moves
 * the smoothed heading by 2 deg, not almost all the way around. [previous]
 * is null before the first usable fix, in which case [target] is returned
 * as-is.
 */
fun smoothBearing(previous: Float?, target: Float, smoothingFactor: Float = 0.3f): Float {
    if (previous == null) return (target % 360f + 360f) % 360f
    val delta = shortestAngleDelta(previous, target)
    return (previous + delta * smoothingFactor + 360f) % 360f
}

private fun shortestAngleDelta(from: Float, to: Float): Float {
    var delta = (to - from) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}
