package it.salvaiciclisti.app.routing

/** One line of a route's turn-by-turn description: a stretch of consecutive
 * steps sharing the same street name, its total length, and (for every line
 * after the first) roughly how sharp the turn onto it was. */
data class RouteInstruction(val streetName: String, val distanceM: Double, val turn: TurnType?)

enum class TurnType {
    DRITTO, LEGGERA_SINISTRA, SINISTRA, NETTA_SINISTRA, LEGGERA_DESTRA, DESTRA, NETTA_DESTRA, INVERSIONE
}

private const val SENZA_NOME = "tratto senza nome"

private fun turnFromAngleDelta(deltaDeg: Double): TurnType {
    val d = ((deltaDeg + 540) % 360) - 180 // normalize to (-180, 180]
    return when {
        d > 150 || d < -150 -> TurnType.INVERSIONE
        d > 100 -> TurnType.NETTA_DESTRA
        d > 45 -> TurnType.DESTRA
        d > 15 -> TurnType.LEGGERA_DESTRA
        d < -100 -> TurnType.NETTA_SINISTRA
        d < -45 -> TurnType.SINISTRA
        d < -15 -> TurnType.LEGGERA_SINISTRA
        else -> TurnType.DRITTO
    }
}

private class StepGroup(val name: String, val steps: List<RouteStep>) {
    val distanceM: Double get() = steps.sumOf { it.lengthM }
}

private fun groupBySameName(route: RouteResult): List<StepGroup> {
    if (route.steps.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<RouteStep>>()
    for (step in route.steps) {
        val name = step.name ?: SENZA_NOME
        val last = groups.lastOrNull()
        if (last != null && (last.first().name ?: SENZA_NOME) == name) {
            last.add(step)
        } else {
            groups.add(mutableListOf(step))
        }
    }
    return groups.map { g -> StepGroup(g.first().name ?: SENZA_NOME, g) }
}

/**
 * Groups a route's steps into named stretches ("Via Nazionale" for 400 m,
 * then a left turn onto "Via IV Novembre" for 120 m, ...) for the route
 * detail screen. Most of the collected cycling-infrastructure sources don't
 * carry a street name, so unnamed steps are grouped under "tratto senza
 * nome" rather than producing one line per anonymous edge.
 */
fun buildInstructions(route: RouteResult): List<RouteInstruction> {
    val groups = groupBySameName(route)
    if (groups.isEmpty()) return emptyList()

    fun bearingAtStart(g: StepGroup): Double? {
        val geom = g.steps.first().geom
        return if (geom.size >= 2) bearing(geom[0], geom[1]) else null
    }
    fun bearingAtEnd(g: StepGroup): Double? {
        val geom = g.steps.last().geom
        return if (geom.size >= 2) bearing(geom[geom.size - 2], geom.last()) else null
    }

    val result = mutableListOf<RouteInstruction>()
    for ((idx, g) in groups.withIndex()) {
        val turn = if (idx == 0) {
            null
        } else {
            val prevBearing = bearingAtEnd(groups[idx - 1])
            val thisBearing = bearingAtStart(g)
            if (prevBearing != null && thisBearing != null) turnFromAngleDelta(thisBearing - prevBearing) else null
        }
        result.add(RouteInstruction(g.name, g.distanceM, turn))
    }
    return result
}
