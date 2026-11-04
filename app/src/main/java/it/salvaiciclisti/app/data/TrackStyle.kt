package it.salvaiciclisti.app.data

/**
 * Colori (ARGB) e larghezze (dp) con cui si disegnano i tratti SIC e i tratti
 * non-SIC (raccordi) - per ciascun percorso 1..N e, con indice 0, per la rete
 * completa sulla mappa (pannello "layers"). Impostabili nella nuova pagina
 * Impostazioni (2026-10-14).
 */
data class TrackStyle(
    val sicColor: Int,
    val raccordiColor: Int,
    val sicWidthDp: Float,
    val raccordiWidthDp: Float,
) {
    companion object {
        const val VERDE = 0xFF2E7D32.toInt()
        const val GIALLO = 0xFFFBC02D.toInt()
        const val BLU = 0xFF1565C0.toInt()
        const val ROSSO = 0xFFC62828.toInt()
        const val CIANO = 0xFF00ACC1.toInt()
        const val MAGENTA = 0xFFC800C8.toInt()

        /** Default concordati con Alessandro: percorso 1 verde/giallo, 2
         * blu/rosso, 3 ciano/magenta, larghezza 3; rete sulla mappa (indice 0)
         * verde/giallo larghezza 1. Dal 4 in poi si ricomincia dal 1. */
        fun defaultFor(index: Int): TrackStyle = when {
            index <= 0 -> TrackStyle(VERDE, GIALLO, 1f, 1f)
            else -> when ((index - 1) % 3) {
                0 -> TrackStyle(VERDE, GIALLO, 3f, 3f)
                1 -> TrackStyle(BLU, ROSSO, 3f, 3f)
                else -> TrackStyle(CIANO, MAGENTA, 3f, 3f)
            }
        }
    }
}

/** Colori proposti nella scelta rapida della pagina Impostazioni. */
val PALETTE_COLORI = listOf(
    "Verde" to TrackStyle.VERDE,
    "Giallo" to TrackStyle.GIALLO,
    "Blu" to TrackStyle.BLU,
    "Rosso" to TrackStyle.ROSSO,
    "Ciano" to TrackStyle.CIANO,
    "Magenta" to TrackStyle.MAGENTA,
    "Arancione" to 0xFFEF6C00.toInt(),
    "Viola" to 0xFF6A1B9A.toInt(),
    "Azzurro" to 0xFF0097A7.toInt(),
    "Marrone" to 0xFF6D4C41.toInt(),
    "Grigio" to 0xFF757575.toInt(),
    "Nero" to 0xFF000000.toInt(),
)
