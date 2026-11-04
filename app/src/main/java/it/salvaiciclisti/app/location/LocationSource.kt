package it.salvaiciclisti.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import it.salvaiciclisti.app.routing.LatLon
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin wrapper around the platform's own LocationManager - deliberately not
 * Google Play Services' FusedLocationProvider, since not every device this
 * app runs on is guaranteed to have GMS. Whichever of GPS/network providers
 * is currently enabled gets used; if neither is, the flow simply never
 * emits.
 *
 * Caller must already hold ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION -
 * this function does not request it, only uses it once granted.
 */
@SuppressLint("MissingPermission")
fun locationUpdates(
    context: Context,
    minTimeMs: Long = 3000L,
    minDistanceM: Float = 5f,
): Flow<Location> = callbackFlow {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            trySend(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    if (providers.isEmpty()) {
        close()
    } else {
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener)
        }
    }

    awaitClose { manager.removeUpdates(listener) }
}

/**
 * One-shot "where am I right now" for the map's default partenza: returns
 * the freshest last-known fix immediately if one exists (usual case, any
 * modern phone has one within seconds of the OS starting location), or
 * waits for the first live fix otherwise. Calls [onResult] with null if no
 * location provider is enabled at all (airplane mode, location off) - the
 * caller is expected to fall back to "manual partenza only" rather than
 * spin forever waiting for a fix that will never come.
 *
 * Must be called from a thread with a running Looper (the main thread is
 * fine, and is what every caller in this app uses) - LocationManager's own
 * requestLocationUpdates needs one to deliver callbacks on.
 */
@SuppressLint("MissingPermission")
fun requestOneShotLocation(context: Context, onResult: (LatLon?) -> Unit) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    val lastKnown = providers
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (lastKnown != null) {
        onResult(LatLon(lastKnown.latitude, lastKnown.longitude))
        return
    }
    if (providers.isEmpty()) {
        onResult(null)
        return
    }

    var delivered = false
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!delivered) {
                delivered = true
                onResult(LatLon(location.latitude, location.longitude))
                manager.removeUpdates(this)
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
}
