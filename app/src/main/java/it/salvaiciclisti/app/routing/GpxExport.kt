package it.salvaiciclisti.app.routing

private fun xmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * Serializes a computed [route] as a plain GPX 1.1 track (each point with
 * <ele> when available and <src> = source of its stretch). This isn't for
 * sharing with other riders (the app has no such feature yet) - it exists so
 * a route that looks wrong on the map (backtracking, a jump through a
 * building) can be opened in a real GIS/GPX tool and inspected point by
 * point against the source data, instead of guessed at from a screenshot.
 * Every vertex of every step is kept as-is, including the repeated point at
 * a step boundary - that duplication is harmless in a GPX viewer and useful
 * here, since it marks exactly where the router moved from one graph edge to
 * the next.
 */
fun routeToGpx(
    route: RouteResult,
    trackName: String = "Percorso Salvaiciclisti",
    description: String? = null,
): String {
    // (2026-10-14) Per ogni punto anche <ele> (quota, se il grafo ne ha) e
    // <src> (la sorgente del tratto: campi standard GPX 1.1 di un punto), cosi'
    // gli script MATLAB possono colorare il percorso per sorgente.
    val trkpts = route.steps.flatMap { step ->
        step.geom.mapIndexed { k, p ->
            val ele = step.ele?.getOrNull(k)?.let { "<ele>%.1f</ele>".format(java.util.Locale.ROOT, it) } ?: ""
            "      <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">$ele<src>${xmlEscape(step.source)}</src></trkpt>"
        }
    }.joinToString("\n")
    // <desc> opzionale del track (standard GPX 1.1): RouteCoherenceTest ci
    // scrive gli indirizzi di partenza e arrivo, letti da plot_all_gpx.m.
    val desc = description?.let { "\n    <desc>${xmlEscape(it)}</desc>" } ?: ""
    return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Salvaiciclisti" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${xmlEscape(trackName)}</name>$desc
    <trkseg>
$trkpts
    </trkseg>
  </trk>
</gpx>
"""
}
