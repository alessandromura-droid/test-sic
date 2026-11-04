package it.salvaiciclisti.app.routing

import it.salvaiciclisti.app.json.SimpleJson
import it.salvaiciclisti.app.json.arr
import it.salvaiciclisti.app.json.num
import it.salvaiciclisti.app.json.obj

/** One searchable street: its name and a handful of representative points
 * along it (built from named-highway OSM ways for the Comune di Roma - see
 * scripts/build_index_indirizzi.py). This is street-level geocoding, not
 * house-number precision: the app doesn't have or need an address database,
 * since the routing step snaps whatever point it gets onto the nearest point
 * of an enabled cycling source anyway (per how "punto non su un percorso
 * noto" is meant to be handled). */
data class StreetEntry(val name: String, val points: List<LatLon>)

class AddressIndex(private val streets: List<StreetEntry>) {
    fun search(query: String, limit: Int = 15): List<StreetEntry> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        return streets
            .filter { normalize(it.name).contains(q) }
            .sortedWith(compareBy({ if (normalize(it.name).startsWith(q)) 0 else 1 }, { it.name }))
            .take(limit)
    }

    companion object {
        private fun normalize(s: String): String =
            s.lowercase()
                .replace("à", "a").replace("è", "e").replace("é", "e")
                .replace("ì", "i").replace("ò", "o").replace("ù", "u")
                .trim()

        fun parse(jsonText: String): AddressIndex {
            val root = SimpleJson.parse(jsonText).obj()
            val streetsObj = root.getValue("strade").obj()
            val entries = streetsObj.entries.map { (name, pointsJson) ->
                val points = pointsJson.arr().map { p ->
                    val pair = p.arr()
                    LatLon(pair[0].num(), pair[1].num())
                }
                StreetEntry(name, points)
            }
            return AddressIndex(entries)
        }
    }
}

/** The search result's point closest to [near] (e.g. the other endpoint
 * already chosen, or the map's current center) rather than always the
 * street's first indexed point - relevant for long streets. */
fun StreetEntry.nearestPointTo(near: LatLon): LatLon =
    points.minByOrNull { haversineMeters(it, near) } ?: points.first()
