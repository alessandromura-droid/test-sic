package it.salvaiciclisti.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import it.salvaiciclisti.app.R
import it.salvaiciclisti.app.routing.LatLon
import it.salvaiciclisti.app.routing.RouteResult
import it.salvaiciclisti.app.routing.buildRouteProfile
import it.salvaiciclisti.app.routing.computeFollowProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val NOTIFICATION_CHANNEL_ID = "navigazione"
private const val NOTIFICATION_ID = 1
private const val POLL_MIN_TIME_MS = 3000L
private const val POLL_MIN_DISTANCE_M = 5f

/** Everything the "Naviga" screen needs to know about the live ride, as one
 * value so producer (this service) and consumer (NavigateScreen) can't
 * drift out of sync one field at a time. */
data class NavState(
    val livePosition: LatLon? = null,
    val headingDeg: Float? = null,
    val distanceFromRouteM: Double? = null,
    val isOnTrack: Boolean = true,
    val remainingDistanceM: Double? = null,
)

/**
 * Foreground service that keeps collecting GPS fixes for as long as
 * "Naviga" is active, including with the screen off or another app in
 * front - a plain [kotlinx.coroutines.flow.Flow] collected inside a
 * Composable's `LaunchedEffect` is torn down the moment Android stops the
 * hosting Activity, which happens quickly once the screen locks, silently
 * cutting off "sei fuori percorso" tracking right when it matters most.
 *
 * Declared with `android:foregroundServiceType="location"` in
 * AndroidManifest.xml - that, plus already holding ACCESS_FINE_LOCATION
 * (requested by the screen before ever starting this service), is enough on
 * its own to keep receiving location updates in the background.
 * Deliberately NOT requesting ACCESS_BACKGROUND_LOCATION anywhere in this
 * app: that permission is only needed for background location access
 * *without* a foreground service running - once this service is up, the
 * exemption already covers it.
 *
 * Simpler than it could be on purpose: a single fixed GPS polling cadence
 * (no adaptive relaxed/tight tiers) and one vibration the moment a fix
 * crosses from on-track to off-track - exactly what was asked for ("ti
 * avverte se sei fuori traccia"), nothing more elaborate.
 *
 * State flows out through the companion [state] StateFlow rather than a
 * bound-service Binder: there is only ever one navigation session and one
 * screen observing it at a time in this single-Activity app.
 */
class NavigationLocationService : Service() {
    private var job: Job? = null

    // Main, not Default: LocationManager.requestLocationUpdates (inside
    // locationUpdates(), called from the coroutine below) creates its own
    // Handler bound to the calling thread, which requires a Looper already
    // running there - Dispatchers.Default's background threads have none.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        _isActive.value = true

        val profile = pendingRoute?.let { buildRouteProfile(it) } ?: emptyList()
        var wasOnTrack = true

        job = scope.launch {
            locationUpdates(applicationContext, POLL_MIN_TIME_MS, POLL_MIN_DISTANCE_M).collect { location ->
                val point = LatLon(location.latitude, location.longitude)
                val previous = _state.value
                val heading = if (location.hasBearing()) {
                    smoothBearing(previous.headingDeg, location.bearing)
                } else {
                    previous.headingDeg
                }
                val progress = if (profile.size >= 2) computeFollowProgress(profile, point) else null

                if (progress != null) {
                    if (wasOnTrack && !progress.isOnTrack) alertOffTrack()
                    wasOnTrack = progress.isOnTrack
                }

                _state.value = NavState(
                    livePosition = point,
                    headingDeg = heading,
                    distanceFromRouteM = progress?.distanceFromRouteM,
                    isOnTrack = progress?.isOnTrack ?: true,
                    remainingDistanceM = progress?.remainingDistanceM,
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        _isActive.value = false
        _state.value = NavState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Vibration plus a short tone, fired once when a fix first crosses from
     * on-track to off-track - a card on screen alone is useless with the
     * phone in a pocket or the screen off, which is exactly when this
     * service is meant to still be useful. Best-effort: a phone in
     * silent/DND mode may not vibrate, and that never blocks navigation
     * either way. */
    private fun alertOffTrack() {
        scope.launch {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(VibratorManager::class.java).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            val toneGenerator = runCatching {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
            }.getOrNull()
            runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400) }
            // ToneGenerator plays asynchronously - releasing it right away
            // would cut the tone off before it's heard.
            delay(500)
            runCatching { toneGenerator?.release() }
        }
    }

    /** Ongoing, low-priority notification required by Android to keep a
     * foreground service alive - deliberately minimal, its only job is to
     * satisfy the platform requirement. */
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(NOTIFICATION_CHANNEL_ID, "Navigazione", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Salvaiciclisti - navigazione in corso")
            .setContentText("Posizione GPS attiva")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private val _state = MutableStateFlow(NavState())
        /** Current fix / smoothed heading / on-track progress - a fresh
         * [NavState] whenever the service isn't running, including right
         * after it's stopped (see [onDestroy]). */
        val state = _state.asStateFlow()

        private val _isActive = MutableStateFlow(false)
        val isActive = _isActive.asStateFlow()

        // Set by start() just before the service actually starts, read once
        // in onCreate() - there's only ever one navigation session at a time
        // in this app, so a plain companion var carries the route across
        // exactly as safely as [state]/[isActive] already do.
        private var pendingRoute: RouteResult? = null

        fun start(context: Context, route: RouteResult) {
            pendingRoute = route
            ContextCompat.startForegroundService(context, Intent(context, NavigationLocationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationLocationService::class.java))
        }
    }
}
