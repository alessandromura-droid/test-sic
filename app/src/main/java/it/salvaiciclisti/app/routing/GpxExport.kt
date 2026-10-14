package it.salvaiciclisti.app.routing

private fun xmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * Serializes a computed [route] as a plain GPX 1.1 track. This isn't for
 * sharing with other riders (the app has no such feature yet) - it exists so
 * a route that looks wrong on the map (backtracking, a jump through a
 * building) can be opened in a real GIS/GPX tool and inspected point by
 * point against the source data, instead of guessed at from a screenshot.
 * Every vertex of every step is kept as-is, including the repeated point at
 * a step boundary - that duplication is harmless in a GPX viewer and useful
 * here, since it marks exactly where the router moved from one graph edge to
 * the next.
 */
fun routeToGpx(route: RouteResult, trackName: String = "Percorso Salvaiciclisti"): String {
    val points = route.steps.flatMap { it.geom }
    val trkpts = points.joinToString("\n") { p ->
        "      <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\" />"
    }
    return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Salvaiciclisti" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${xmlEscape(trackName)}</name>
    <trkseg>
$trkpts
    </trkseg>
  </trk>
</gpx>
"""
}
