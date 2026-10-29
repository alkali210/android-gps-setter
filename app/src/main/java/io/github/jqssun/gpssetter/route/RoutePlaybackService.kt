package io.github.jqssun.gpssetter.route

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Foreground service that walks a route and feeds moving fixes through PrefManager at ~1 Hz.
// The hook reads lat/lng/altitude/speed/bearing from the mirrored settings, so apps see real motion.
class RoutePlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PrefManager.update(true, PrefManager.getLat, PrefManager.getLng) // freeze at current point, clear motion
            stopEverything()
            return START_NOT_STICKY
        }
        val lats = intent?.getDoubleArrayExtra(EXTRA_LAT)
        val lngs = intent?.getDoubleArrayExtra(EXTRA_LNG)
        val eles = intent?.getDoubleArrayExtra(EXTRA_ELE)
        val speed = intent?.getFloatExtra(EXTRA_SPEED, 0f) ?: 0f
        val loop = intent?.getBooleanExtra(EXTRA_LOOP, false) ?: false
        if (lats == null || lngs == null || lats.size < 2 || lats.size != lngs.size || speed <= 0f) {
            stopSelf()
            return START_NOT_STICKY
        }
        val route = lats.indices.map { RoutePoint(lats[it], lngs[it], eles?.getOrNull(it) ?: 0.0) }

        startForegroundSafely()
        loopJob?.cancel()
        gpxPlayback.value = intent?.getBooleanExtra(EXTRA_GPX, false) == true
        loopJob = scope.launch { run(route, speed, loop) }
        return START_NOT_STICKY
    }

    private suspend fun run(route: List<RoutePoint>, speed: Float, loop: Boolean) {
        val total = RouteMath.pathLength(route)
        val accel = speed / 4.0 // ramp from 0 to target over ~4 s, so starts/stops feel real
        var curSpeed = 0.0
        var distance = 0.0
        var last = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last) / 1000.0
            curSpeed = (curSpeed + accel * dt).coerceAtMost(speed.toDouble())
            distance += curSpeed * dt
            last = now
            if (distance >= total) {
                if (loop) {
                    distance = 0.0
                } else {
                    val end = route.last()
                    PrefManager.update(true, end.lat, end.lng) // settle at the end, clear motion
                    stopEverything()
                    return
                }
            }
            RouteMath.pointAtDistance(route, distance)?.let { fix ->
                PrefManager.updateMotion(fix.lat, fix.lng, fix.altitude, curSpeed.toFloat(), fix.bearing)
                notify(progress = if (total > 0) (distance / total * 100).toInt().coerceIn(0, 100) else 0)
            }
            delay(1000)
        }
    }

    private fun stopEverything() {
        loopJob?.cancel()
        gpxPlayback.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        gpxPlayback.value = false
    }

    // --- notification ---

    private fun ensureChannel() {
        NotificationChannelCompat.Builder(CHANNEL, NotificationManager.IMPORTANCE_LOW)
            .setName(getString(R.string.route_channel)).build()
            .also { NotificationManagerCompat.from(this).createNotificationChannel(it) }
    }

    private fun build(progress: Int): Notification {
        ensureChannel()
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RoutePlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.route_playing))
            .setContentText("$progress%")
            .setOngoing(true)
            .setProgress(100, progress, false)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stop)
            .build()
    }

    private fun startForegroundSafely() {
        val n = build(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(progress: Int) =
        NotificationManagerCompat.from(this).notify(NOTIF_ID, build(progress))

    companion object {
        private val gpxPlayback = MutableStateFlow(false)
        val isGpxPlaying = gpxPlayback.asStateFlow()
        private const val EXTRA_GPX = "gpx"
        private const val CHANNEL = "route.playback"
        private const val NOTIF_ID = 321
        const val ACTION_STOP = "io.github.jqssun.gpssetter.route.STOP"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_ELE = "ele"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_LOOP = "loop"

        fun start(context: Context, route: List<RoutePoint>, speedMps: Float, loop: Boolean, gpx: Boolean = false) {
            val intent = Intent(context, RoutePlaybackService::class.java)
                .putExtra(EXTRA_LAT, DoubleArray(route.size) { route[it].lat })
                .putExtra(EXTRA_LNG, DoubleArray(route.size) { route[it].lng })
                .putExtra(EXTRA_ELE, DoubleArray(route.size) { route[it].altitude })
                .putExtra(EXTRA_SPEED, speedMps)
                .putExtra(EXTRA_LOOP, loop)
                .putExtra(EXTRA_GPX, gpx)
            val previous = gpxPlayback.value
            gpxPlayback.value = gpx
            try {
                context.startForegroundService(intent)
            } catch (e: RuntimeException) {
                gpxPlayback.value = previous
                throw e
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RoutePlaybackService::class.java).setAction(ACTION_STOP))
        }
    }
}
