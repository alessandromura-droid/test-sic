package it.salvaiciclisti.app.data

import android.content.Context
import it.salvaiciclisti.app.map.MapStyle
import it.salvaiciclisti.app.routing.AppRoutingParams
import it.salvaiciclisti.app.routing.RoutingEngine
import it.salvaiciclisti.app.routing.RuleParams

private const val PREFS_NAME = "salvaiciclisti_settings"
private const val KEY_ENABLED_SOURCES = "enabled_sources"
private const val KEY_ROUTING_ENGINE = "routing_engine"
private const val KEY_AUTO_UPDATE_MAP_DATA = "auto_update_map_data"
private const val KEY_SOURCE_COLOR_PREFIX = "source_color_"
private const val KEY_ROUTES_PER_RULE = "routes_per_rule"
private const val KEY_RULE_BUDGET_CAP_PERCENT = "rule_budget_cap_percent"
private const val KEY_RULE_BUDGET_SIC_WEIGHT = "rule_budget_sic_weight"
private const val KEY_RULE_SOURCE_SURCHARGE_M = "rule_source_surcharge_m"
private const val KEY_RULE_DIRECTIONAL_BACKWARD_PERCENT = "rule_directional_backward_percent"
private const val KEY_RULE_DIRECTIONAL_SIC_WEIGHT = "rule_directional_sic_weight"
private const val KEY_ENABLED_DETAIL_LAYERS = "enabled_detail_layers"
private const val KEY_MAP_STYLE = "map_style"
// Nuova pagina Impostazioni (2026-10-14)
private const val KEY_APP_ENGINE = "app_engine"
private const val KEY_APP_NUM_ROUTES = "app_num_routes"
private const val KEY_APP_PESO_SIC = "app_peso_sic"
private const val KEY_APP_PESO_RACCORDI = "app_peso_raccordi"
private const val KEY_APP_PESO_CICLOPOLITANA = "app_peso_ciclopolitana_mancante"
private const val KEY_APP_PESO_VERDE = "app_peso_verde"
private const val KEY_APP_PESO_SCOLASTICHE = "app_peso_scolastiche"
private const val KEY_APP_PESO_CANTIERI = "app_peso_cantieri"
private const val KEY_APP_PESO_RIMOSSE = "app_peso_rimosse"
private const val KEY_APP_EVITA_PARCHI = "app_evita_parchi"
private const val KEY_APP_PESO_KM = "app_peso_km"
private const val KEY_APP_PESO_SALITA = "app_peso_salita"
private const val KEY_APP_MARGINE = "app_margine_percent"
private const val KEY_APP_ARRETRAMENTO = "app_arretramento_percent"
private const val KEY_APP_RAGGIO_MIGLIO = "app_raggio_primo_ultimo_miglio_km"
private const val KEY_STYLE_PREFIX = "style_"
private const val KEY_NETWORK_OVERLAY = "network_overlay_sources"
private const val KEY_RETI_ATTIVE = "reti_attive"
const val MAX_NUMERO_PERCORSI = 6

/** (2026-11-20) Rete che collega SIC, cioe' quale grafo usa l'app
 * ((2026-11-21) di default entrambe, vedi [SettingsRepository.getRetiAttive]):
 * [RACCORDI] = graph.json (SIC + raccordi + accessi, deterministici),
 * [HEATMAP] = graph_heatmap.json (SIC + strade piu' percorse della heat map). */
enum class ReteSupporto { RACCORDI, HEATMAP }

/**
 * Tutte le preferenze persistite dalla schermata Impostazioni. Defaults:
 * - sorgenti abilitate: SIC e OSM soltanto (vedi [SourceDisplay]), non piu'
 *   "tutte" - scelta esplicita di Alessandro il 2026-10-02, le altre tre
 *   restano disponibili ma partono spente ("do not use").
 * - colore per sorgente: quello di [SourceDisplay.defaultsFor] finche' non
 *   personalizzato da Impostazioni.
 * - parametri delle tre regole di routing (vedi RoutingRules.kt per cosa
 *   fa ciascuno): i default di [RuleParams] stesso.
 */
object SettingsRepository {
    fun getEnabledSources(context: Context, allKeys: List<String>): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Defensive copy: SharedPreferences.getStringSet's own docs warn
        // against mutating (or holding onto across an editor.apply()) the
        // Set instance it returns.
        val stored = prefs.getStringSet(KEY_ENABLED_SOURCES, null)?.toSet()
        return stored ?: allKeys.filter { SourceDisplay.defaultsFor(it).defaultEnabled }.toSet()
    }

    fun setEnabledSources(context: Context, enabled: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_ENABLED_SOURCES, enabled).apply()
    }

    /**
     * Which pathfinding engine computes routes - in-house Dijkstra (the
     * original, hand-rolled implementation) or JGraphT (the well-tested
     * open-source graph library). Defaults to IN_HOUSE so existing installs
     * see no behaviour change until the user opts into JGraphT from
     * Impostazioni.
     */
    fun getRoutingEngine(context: Context): RoutingEngine {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ROUTING_ENGINE, null)
        return stored?.let { name ->
            runCatching { RoutingEngine.valueOf(name) }.getOrNull()
        } ?: RoutingEngine.IN_HOUSE
    }

    fun setRoutingEngine(context: Context, engine: RoutingEngine) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ROUTING_ENGINE, engine.name).apply()
    }

    /**
     * Se true, un aggiornamento dei dati mappa trovato su GitHub Releases
     * (vedi RemoteMapDataRepository/BackgroundUpdateCheck in MainActivity.kt)
     * viene scaricato subito senza chiedere conferma - resta comunque
     * necessario riavviare l'app per usarlo (il grafo gia' caricato in
     * memoria non viene sostituito a caldo). Default false: Alessandro non
     * ha chiesto di scaricare dati in automatico senza chiederlo, meglio
     * chiedere finche' non lo si attiva esplicitamente da Impostazioni.
     */
    fun getAutoUpdateMapData(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_UPDATE_MAP_DATA, false)
    }

    fun setAutoUpdateMapData(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_UPDATE_MAP_DATA, enabled).apply()
    }

    /** Colore (ARGB) di una sorgente, personalizzato o di default - 0 non e'
     * mai un colore valido scelto dall'utente (alpha pieno sempre acceso),
     * quindi serve da sentinella "non ancora personalizzato" senza bisogno
     * di un secondo flag. */
    fun getSourceColor(context: Context, sourceKey: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt(KEY_SOURCE_COLOR_PREFIX + sourceKey, 0)
        return if (stored != 0) stored else SourceDisplay.defaultsFor(sourceKey).defaultColorArgb
    }

    fun setSourceColor(context: Context, sourceKey: String, colorArgb: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SOURCE_COLOR_PREFIX + sourceKey, colorArgb).apply()
    }

    /** Quanti percorsi mostrare per ciascuna delle tre regole di routing
     * (vedi RoutingRules.kt) - con il default 2, "fino a 6 alternative"
     * come concordato (3 regole x 2). */
    fun getRoutesPerRule(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ROUTES_PER_RULE, 2).coerceIn(1, 4)
    }

    fun setRoutesPerRule(context: Context, value: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_ROUTES_PER_RULE, value.coerceIn(1, 4)).apply()
    }

    /** Parametri delle tre regole, personalizzati o i default di
     * [RuleParams]. [SourceDisplay.SIC_KEY] resta fisso (non e' pensato
     * come personalizzabile: e' la sorgente "ufficiale" che le regole
     * preferiscono per costruzione, non un numero a piacere). */
    fun getRuleParams(context: Context): RuleParams {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = RuleParams()
        return RuleParams(
            preferredSourceKey = SourceDisplay.SIC_KEY,
            budgetCapPercent = prefs.getFloat(KEY_RULE_BUDGET_CAP_PERCENT, defaults.budgetCapPercent.toFloat()).toDouble(),
            budgetSicWeight = prefs.getFloat(KEY_RULE_BUDGET_SIC_WEIGHT, defaults.budgetSicWeight.toFloat()).toDouble(),
            sourceSurchargeM = prefs.getFloat(KEY_RULE_SOURCE_SURCHARGE_M, defaults.sourceSurchargeM.toFloat()).toDouble(),
            directionalBackwardPercent = prefs.getFloat(
                KEY_RULE_DIRECTIONAL_BACKWARD_PERCENT,
                defaults.directionalBackwardPercent.toFloat(),
            ).toDouble(),
            directionalSicWeight = prefs.getFloat(
                KEY_RULE_DIRECTIONAL_SIC_WEIGHT,
                defaults.directionalSicWeight.toFloat(),
            ).toDouble(),
        )
    }

    fun setRuleParams(context: Context, params: RuleParams) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_RULE_BUDGET_CAP_PERCENT, params.budgetCapPercent.toFloat())
            .putFloat(KEY_RULE_BUDGET_SIC_WEIGHT, params.budgetSicWeight.toFloat())
            .putFloat(KEY_RULE_SOURCE_SURCHARGE_M, params.sourceSurchargeM.toFloat())
            .putFloat(KEY_RULE_DIRECTIONAL_BACKWARD_PERCENT, params.directionalBackwardPercent.toFloat())
            .putFloat(KEY_RULE_DIRECTIONAL_SIC_WEIGHT, params.directionalSicWeight.toFloat())
            .apply()
    }

    /** Quali layer di dettaglio (bike box, rastrelliere, punti critici,
     * passaggi segreti... - vedi [it.salvaiciclisti.app.routing.DetailLayer])
     * sono visibili sul pulsante "layers" della mappa - indipendenti dalle
     * sorgenti abilitate per il routing ([getEnabledSources]): un layer puo'
     * essere visibile anche se la sua sorgente non partecipa al calcolo del
     * percorso. Default: tutti visibili, l'utente li spegne dal pannello. */
    fun getEnabledDetailLayers(context: Context, allKeys: List<String>): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_ENABLED_DETAIL_LAYERS, null)?.toSet()
        return stored ?: allKeys.toSet()
    }

    fun setEnabledDetailLayers(context: Context, enabled: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_ENABLED_DETAIL_LAYERS, enabled).apply()
    }

    /** Stile della mappa di base (vedi [MapStyle]) - default MAPNIK, nessun
     * cambio di comportamento per chi non tocca mai questa impostazione. */
    fun getMapStyle(context: Context): MapStyle {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MAP_STYLE, null)
        return stored?.let { name ->
            runCatching { MapStyle.valueOf(name) }.getOrNull()
        } ?: MapStyle.MAPNIK
    }

    fun setMapStyle(context: Context, style: MapStyle) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MAP_STYLE, style.name).apply()
    }

    // ------------------------------------------------------------------
    // Nuova pagina Impostazioni (2026-10-14) - vedi ImpostazioniScreen.kt.
    // ------------------------------------------------------------------

    /** Parametri di calcolo della nuova pagina (vedi [AppRoutingParams]). Il
     * motore ha una chiave propria, separata da quella della vecchia pagina. */
    fun getAppRoutingParams(context: Context): AppRoutingParams {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val d = AppRoutingParams()
        val engine = prefs.getString(KEY_APP_ENGINE, null)
            ?.let { runCatching { RoutingEngine.valueOf(it) }.getOrNull() } ?: d.engine
        return AppRoutingParams(
            engine = engine,
            numeroPercorsi = prefs.getInt(KEY_APP_NUM_ROUTES, d.numeroPercorsi).coerceIn(1, MAX_NUMERO_PERCORSI),
            pesoSic = prefs.getFloat(KEY_APP_PESO_SIC, d.pesoSic.toFloat()).toDouble(),
            pesoRaccordi = prefs.getFloat(KEY_APP_PESO_RACCORDI, d.pesoRaccordi.toFloat()).toDouble(),
            pesoCiclopolitana = prefs.getFloat(KEY_APP_PESO_CICLOPOLITANA, d.pesoCiclopolitana.toFloat()).toDouble(),
            pesoVerde = prefs.getFloat(KEY_APP_PESO_VERDE, d.pesoVerde.toFloat()).toDouble(),
            pesoScolastiche = prefs.getFloat(KEY_APP_PESO_SCOLASTICHE, d.pesoScolastiche.toFloat()).toDouble(),
            pesoCantieri = prefs.getFloat(KEY_APP_PESO_CANTIERI, d.pesoCantieri.toFloat()).toDouble(),
            pesoRimosse = prefs.getFloat(KEY_APP_PESO_RIMOSSE, d.pesoRimosse.toFloat()).toDouble(),
            evitaParchi = prefs.getBoolean(KEY_APP_EVITA_PARCHI, d.evitaParchi),
            pesoKm = prefs.getFloat(KEY_APP_PESO_KM, d.pesoKm.toFloat()).toDouble(),
            pesoSalita = prefs.getFloat(KEY_APP_PESO_SALITA, d.pesoSalita.toFloat()).toDouble(),
            margineMassimoPercent = prefs.getFloat(KEY_APP_MARGINE, d.margineMassimoPercent.toFloat()).toDouble(),
            arretramentoMassimoPercent = prefs.getFloat(
                KEY_APP_ARRETRAMENTO,
                d.arretramentoMassimoPercent.toFloat(),
            ).toDouble(),
            raggioPrimoUltimoMiglioKm = prefs.getFloat(KEY_APP_RAGGIO_MIGLIO, d.raggioPrimoUltimoMiglioKm.toFloat()).toDouble(),
            sicKey = SourceDisplay.SIC_KEY,
        )
    }

    fun setAppRoutingParams(context: Context, p: AppRoutingParams) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_APP_ENGINE, p.engine.name)
            .putInt(KEY_APP_NUM_ROUTES, p.numeroPercorsi.coerceIn(1, MAX_NUMERO_PERCORSI))
            .putFloat(KEY_APP_PESO_SIC, p.pesoSic.toFloat())
            .putFloat(KEY_APP_PESO_RACCORDI, p.pesoRaccordi.toFloat())
            .putFloat(KEY_APP_PESO_CICLOPOLITANA, p.pesoCiclopolitana.toFloat())
            .putFloat(KEY_APP_PESO_VERDE, p.pesoVerde.toFloat())
            .putFloat(KEY_APP_PESO_SCOLASTICHE, p.pesoScolastiche.toFloat())
            .putFloat(KEY_APP_PESO_CANTIERI, p.pesoCantieri.toFloat())
            .putFloat(KEY_APP_PESO_RIMOSSE, p.pesoRimosse.toFloat())
            .putBoolean(KEY_APP_EVITA_PARCHI, p.evitaParchi)
            .putFloat(KEY_APP_PESO_KM, p.pesoKm.toFloat())
            .putFloat(KEY_APP_PESO_SALITA, p.pesoSalita.toFloat())
            .putFloat(KEY_APP_MARGINE, p.margineMassimoPercent.toFloat())
            .putFloat(KEY_APP_ARRETRAMENTO, p.arretramentoMassimoPercent.toFloat())
            .putFloat(KEY_APP_RAGGIO_MIGLIO, p.raggioPrimoUltimoMiglioKm.toFloat())
            .apply()
    }

    /** Colori e larghezze (dp) di SIC e raccordi per il percorso numero
     * [index] (1..), oppure per la rete sulla mappa con [index] = 0. */
    fun getTrackStyle(context: Context, index: Int): TrackStyle {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val d = TrackStyle.defaultFor(index)
        val k = KEY_STYLE_PREFIX + index + "_"
        return TrackStyle(
            sicColor = prefs.getInt(k + "sic_color", d.sicColor),
            raccordiColor = prefs.getInt(k + "raccordi_color", d.raccordiColor),
            sicWidthDp = prefs.getFloat(k + "sic_width", d.sicWidthDp),
            raccordiWidthDp = prefs.getFloat(k + "raccordi_width", d.raccordiWidthDp),
        )
    }

    fun setTrackStyle(context: Context, index: Int, style: TrackStyle) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val k = KEY_STYLE_PREFIX + index + "_"
        prefs.edit()
            .putInt(k + "sic_color", style.sicColor)
            .putInt(k + "raccordi_color", style.raccordiColor)
            .putFloat(k + "sic_width", style.sicWidthDp)
            .putFloat(k + "raccordi_width", style.raccordiWidthDp)
            .apply()
    }

    /** Sorgenti di cui e' visualizzata la rete completa (pannello "layers"
     * della mappa) - ricordate fra un'apertura e l'altra (2026-10-14:
     * "visualizza SIC" e "visualizza raccordi" devono restare memorizzati). */
    fun getNetworkOverlaySources(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_NETWORK_OVERLAY, null)?.toSet() ?: emptySet()
    }

    fun setNetworkOverlaySources(context: Context, sources: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_NETWORK_OVERLAY, sources).apply()
    }

    /** (2026-11-21) Reti di supporto attive (vedi [ReteSupporto]): di
     * default ENTRAMBE (l'app propone percorsi su tutte e due, vedi
     * unisciPercorsiReti in AppRouting.kt). Mai vuoto: vedi [cambiaRete].
     * Se il grafo heat map non c'e', AssetRepository.retiEffettive la toglie. */
    fun getRetiAttive(context: Context): Set<ReteSupporto> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_RETI_ATTIVE, null)
            ?.mapNotNull { runCatching { ReteSupporto.valueOf(it) }.getOrNull() }?.toSet()
        return if (set.isNullOrEmpty()) ReteSupporto.values().toSet() else set
    }

    fun setRetiAttive(context: Context, reti: Set<ReteSupporto>) {
        val valide = reti.ifEmpty { ReteSupporto.values().toSet() }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_RETI_ATTIVE, valide.map { it.name }.toSet()).apply()
    }

    /** Accende o spegne [rete] e ritorna l'insieme risultante (gia' salvato).
     * Spegnendo l'ultima rete accesa si riaccendono le altre, cioe' (con due
     * reti) quella spenta per penultima (Alessandro, 2026-11-21). */
    fun cambiaRete(context: Context, rete: ReteSupporto, accesa: Boolean): Set<ReteSupporto> {
        val attuali = getRetiAttive(context)
        var nuove = if (accesa) attuali + rete else attuali - rete
        if (nuove.isEmpty()) nuove = ReteSupporto.values().toSet() - rete
        setRetiAttive(context, nuove)
        return nuove
    }
}
