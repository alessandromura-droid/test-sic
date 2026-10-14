package it.salvaiciclisti.app.map

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox

private var osmdroidConfigured = false

/**
 * Roughly the extent of the Citta' Metropolitana di Roma Capitale (the
 * former "provincia di Roma") - coast at Fiumicino/Civitavecchia in the
 * west to the Rieti/Frosinone border hill towns in the east. Deliberately a
 * generous bounding box rather than the exact administrative polygon:
 * restricting the map via MapView.setScrollableAreaLimitDouble is the
 * practical osmdroid mechanism for "non deve mostrare tile fuori dalla
 * provincia di Roma" - a precise polygon clip isn't worth the extra
 * complexity for what is, in practice, "don't let the map wander off to a
 * different region of Italy". Adjust these four numbers if the real extent
 * needs to be tighter or looser.
 */
val ROMA_METROPOLITANA_BBOX: BoundingBox = BoundingBox(42.20, 13.25, 41.35, 11.70)

const val ROMA_CENTER_LAT = 41.9028
const val ROMA_CENTER_LON = 12.4964

/**
 * osmdroid setup shared by every map screen: cache path/user-agent, and a
 * tile cache used as a growing offline cache ("le scarica la prima volta e
 * poi rimangono in cache man mano che esplori o zoomi") - large enough that
 * ordinary use of the metropolitan-city area doesn't evict tiles just to
 * make room for a new one, plus expirationOverrideDuration so a cached tile
 * is never re-fetched just because the server's own cache-control header
 * calls it stale (a large cache alone only stops eviction, not
 * re-fetching). Runs once per process.
 */
fun ensureOsmdroidConfigured(context: Context) {
    if (osmdroidConfigured) return
    val appContext = context.applicationContext
    val config = Configuration.getInstance()
    config.load(appContext, appContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    config.userAgentValue = appContext.packageName
    config.tileFileSystemCacheMaxBytes = 500L * 1024 * 1024
    config.tileFileSystemCacheTrimBytes = 450L * 1024 * 1024
    config.expirationOverrideDuration = Long.MAX_VALUE
    osmdroidConfigured = true
}
