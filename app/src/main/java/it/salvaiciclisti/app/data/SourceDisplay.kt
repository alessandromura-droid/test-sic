package it.salvaiciclisti.app.data

import it.salvaiciclisti.app.routing.SourceInfo

/** Ordine fisso, colore di default e se partecipa al routing di default per
 * una delle sorgenti note - vedi [SourceDisplay]. [belowSeparator] e' il
 * separatore "do not use" chiesto da Alessandro (2026-10-02): solo SIC e
 * OSM nascono accese, tutte le altre sotto il separatore nascono spente. */
data class SourceDefaults(
    val order: Int,
    val defaultColorArgb: Int,
    val defaultEnabled: Boolean,
    val belowSeparator: Boolean,
)

/**
 * Un solo posto che raccoglie ordine/colore/accensione di default per
 * ciascuna delle sorgenti raccolte - usato da SettingsScreen per ordinare la
 * lista con separatore e da MapScreen per colorare i segmenti di percorso
 * per sorgente (vedi anche [it.salvaiciclisti.app.data.SettingsRepository]
 * per gli overrides configurabili, che partono da questi default). I colori
 * sono solo un punto di partenza ragionevole, non un vincolo - Alessandro ha
 * chiesto che siano personalizzabili, vedi SettingsRepository.getSourceColor.
 */
object SourceDisplay {
    const val SIC_KEY = "salvaiciclisti_sicrm"

    private val DEFAULTS: Map<String, SourceDefaults> = mapOf(
        SIC_KEY to SourceDefaults(order = 0, defaultColorArgb = 0xFF2E7D32.toInt(), defaultEnabled = true, belowSeparator = false), // verde
        "osm_extract" to SourceDefaults(order = 1, defaultColorArgb = 0xFFC62828.toInt(), defaultEnabled = true, belowSeparator = false), // rosso
        // Raccordi (scripts/raccordi.py, 2026-10-13): tratti di strada che
        // collegano SIC - accesi di default, sopra il separatore.
        "raccordi" to SourceDefaults(order = 2, defaultColorArgb = 0xFFEF6C00.toInt(), defaultEnabled = true, belowSeparator = false), // arancione
        "g3w_ciclovie_metropolitane" to SourceDefaults(order = 3, defaultColorArgb = 0xFF1565C0.toInt(), defaultEnabled = false, belowSeparator = true), // blu
        "salvaiciclisti_ciclopolitana" to SourceDefaults(order = 4, defaultColorArgb = 0xFFC800C8.toInt(), defaultEnabled = false, belowSeparator = true), // magenta
        "rsm_piste_ciclabili" to SourceDefaults(order = 5, defaultColorArgb = 0xFF0097A7.toInt(), defaultEnabled = false, belowSeparator = true), // azzurro
    )

    /** Per una sorgente non ancora catalogata sopra (es. una aggiunta in
     * futuro a SOURCE_LABELS e non ancora vista qui): finisce in coda, sotto
     * il separatore, spenta di default - mai persa silenziosamente dalla
     * lista, ma anche mai accesa senza che qualcuno lo decida esplicitamente. */
    private val FALLBACK = SourceDefaults(order = 99, defaultColorArgb = 0xFF757575.toInt(), defaultEnabled = false, belowSeparator = true)

    fun defaultsFor(key: String): SourceDefaults = DEFAULTS[key] ?: FALLBACK

    /** Le sorgenti note nell'ordine fisso SIC, OSM, (separatore), le altre -
     * una qualunque non catalogata finisce comunque in coda, non sparisce. */
    fun sortedForSettings(sources: List<SourceInfo>): List<SourceInfo> =
        sources.sortedBy { defaultsFor(it.key).order }

    /** Indice (dentro una lista gia' ordinata con [sortedForSettings]) del
     * primo elemento sotto il separatore "do not use" - -1 se nessuno. */
    fun firstBelowSeparatorIndex(sorted: List<SourceInfo>): Int =
        sorted.indexOfFirst { defaultsFor(it.key).belowSeparator }
}

/**
 * Simbolo distinto (un singolo carattere Unicode, disegnato con
 * Marker.setTextIcon - stessa API gia' usata per i marker numerati dei
 * percorsi in MapScreen.kt, quindi nessuna nuova dipendenza grafica) per
 * ciascun layer di dettaglio dentro la stessa sorgente (bike box,
 * rastrelliere, punti critici, ecc. - vedi DETAIL_LAYER_FILES in
 * scripts/build_routing_graph.py, che genera le chiavi usate qui). Oggi
 * appartengono tutti alla sorgente SIC, quindi condividono il suo colore
 * (MapScreen li disegna in sourceColors[layer.source]) e si distinguono
 * solo per simbolo - esattamente come chiesto da Alessandro il 2026-10-02
 * ("così anche in settings li riconosci bene").
 */
object DetailLayerDisplay {
    private val SYMBOL_ORDER = listOf(
        "sic_punti_riparazione",
        "sic_ciclostazioni",
        "sic_passaggi_segreti",
        "sic_bikeboxes",
        "sic_negozi_bike_friendly",
        "sic_rastrelliere",
        "sic_punti_critici",
        "sic_autorimesse_bike_friendly",
        "sic_strade_scolastiche",
    )
    private val SYMBOLS = listOf("●", "▲", "■", "★", "✚", "◆", "▼", "✱", "○")

    /** "•" di riserva per un layer non ancora catalogato sopra (es. uno
     * nuovo aggiunto in futuro a DETAIL_LAYER_FILES) - mai un crash, solo un
     * simbolo meno distintivo finche' qualcuno non lo aggiunge qui. */
    fun symbolFor(key: String): String {
        val idx = SYMBOL_ORDER.indexOf(key)
        return if (idx in SYMBOLS.indices) SYMBOLS[idx] else "•"
    }
}
