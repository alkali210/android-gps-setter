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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.random.Random

// Foreground service that walks a route and feeds moving fixes through PrefManager at ~1 Hz.
// The hook reads lat/lng/altitude/speed/bearing from the mirrored settings, so apps see real motion.
// Playback maths (modes, speed variation, skipping) lives in RouteMath, where it's unit-tested.
class RoutePlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var loopJob: Job? = null
    @Volatile private var paused = false
    @Volatile private var skip = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                PrefManager.update(true, PrefManager.getLat, PrefManager.getLng) // freeze at current point, clear motion
                stopEverything()
                return START_NOT_STICKY
            }
            // pause eases to a stop where you are; resume speeds back up
            ACTION_PAUSE -> {
                if (loopJob?.isActive != true) { stopSelf(); return START_NOT_STICKY }
                paused = !paused
                PrefManager.routeState = if (paused) PAUSED else RUNNING
                return START_NOT_STICKY
            }
            ACTION_SKIP -> {
                if (loopJob?.isActive != true) { stopSelf(); return START_NOT_STICKY }
                skip = true
                return START_NOT_STICKY
            }
        }
        val lats = intent?.getDoubleArrayExtra(EXTRA_LAT)
        val lngs = intent?.getDoubleArrayExtra(EXTRA_LNG)
        val eles = intent?.getDoubleArrayExtra(EXTRA_ELE)
        val speed = intent?.getFloatExtra(EXTRA_SPEED, 0f) ?: 0f
        val variation = intent?.getFloatExtra(EXTRA_VARIATION, 0f) ?: 0f
        val mode = intent?.getIntExtra(EXTRA_MODE, RouteMath.ONCE) ?: RouteMath.ONCE
        val stops = intent?.getDoubleArrayExtra(EXTRA_STOPS) ?: DoubleArray(0)
        if (lats == null || lngs == null || lats.size < 2 || lats.size != lngs.size || speed <= 0f) {
            stopEverything()
            return START_NOT_STICKY
        }
        val route = lats.indices.map { RoutePoint(lats[it], lngs[it], eles?.getOrNull(it) ?: 0.0) }

        startForegroundSafely()
        loopJob?.cancel()
        paused = false
        skip = false
        PrefManager.routeState = RUNNING
        loopJob = scope.launch { run(route, speed.toDouble(), variation.toDouble(), mode, stops) }
        return START_NOT_STICKY
    }

    private suspend fun run(route: List<RoutePoint>, speed: Double, variation: Double, mode: Int, stops: DoubleArray) {
        val total = RouteMath.pathLength(route)
        val accel = speed / 4.0 // 0 to full speed (or back) in ~4 s, so starts, pauses and stops feel real
        // slow for sharp turns: brake ahead of each one (no harder than we can at this speed), then pick up again
        val corners = RouteMath.corners(route)
        val brake = min(2.0, accel)
        val phase = Random.nextDouble(2 * PI)
        var curSpeed = 0.0
        var distance = 0.0
        var dir = 1
        val start = SystemClock.elapsedRealtime()
        var last = start
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last) / 1000.0
            last = now
            if (skip) {
                skip = false
                distance = RouteMath.nextStop(stops, distance, dir, total)
            }
            val target = if (paused) 0.0 else min(
                speed * RouteMath.speedFactor(variation, (now - start) / 1000.0, phase),
                RouteMath.cornerLimit(corners, distance, dir, brake)
            )
            curSpeed = RouteMath.approach(curSpeed, target, accel * dt)
            val p = RouteMath.advance(distance, dir, curSpeed * dt, total, mode)
            distance = p.distance
            dir = p.dir
            if (p.done) {
                val end = route.last()
                PrefManager.update(true, end.lat, end.lng) // settle at the end, clear motion
                stopEverything()
                return
            }
            RouteMath.pointAtDistance(route, distance)?.let { fix ->
                // coming back on a ping-pong: face the other way
                val bearing = if (dir < 0) (fix.bearing + 180f) % 360f else fix.bearing
                PrefManager.updateMotion(fix.lat, fix.lng, fix.altitude, curSpeed.toFloat(), bearing, distance.toFloat())
                notify(progress = if (total > 0) (distance / total * 100).toInt().coerceIn(0, 100) else 0)
            }
            delay(1000)
        }
    }

    private fun stopEverything() {
        loopJob?.cancel()
        PrefManager.routeState = IDLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // --- notification ---

    private fun ensureChannel() {
        NotificationChannelCompat.Builder(CHANNEL, NotificationManager.IMPORTANCE_LOW)
            .setName(getString(R.string.route_channel)).build()
            .also { NotificationManagerCompat.from(this).createNotificationChannel(it) }
    }

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RoutePlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(progress: Int): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(if (paused) R.string.route_paused else R.string.route_playing))
            .setContentText("$progress%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .addAction(R.drawable.ic_play, getString(if (paused) R.string.route_resume else R.string.route_pause), action(ACTION_PAUSE, 1))
            .addAction(R.drawable.ic_stop, getString(R.string.stop), action(ACTION_STOP, 0))
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
        const val IDLE = 0
        const val RUNNING = 1
        const val PAUSED = 2

        private const val CHANNEL = "route.playback"
        private const val NOTIF_ID = 321
        const val ACTION_STOP = "io.github.jqssun.gpssetter.route.STOP"
        private const val ACTION_PAUSE = "io.github.jqssun.gpssetter.route.PAUSE" // toggles
        private const val ACTION_SKIP = "io.github.jqssun.gpssetter.route.SKIP"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_ELE = "ele"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_VARIATION = "variation"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_STOPS = "stops"

        // [variation] is a fraction (0.12 = ±12%), [mode] a RouteMath mode, [stops] the cumulative
        // distances of the via stops and destination, for skipping
        fun start(context: Context, route: List<RoutePoint>, speedMps: Float, variation: Float, mode: Int, stops: DoubleArray) {
            PrefManager.routeState = RUNNING // now, so the Routes tab doesn't read IDLE before the service starts
            val intent = Intent(context, RoutePlaybackService::class.java)
                .putExtra(EXTRA_LAT, DoubleArray(route.size) { route[it].lat })
                .putExtra(EXTRA_LNG, DoubleArray(route.size) { route[it].lng })
                .putExtra(EXTRA_ELE, DoubleArray(route.size) { route[it].altitude })
                .putExtra(EXTRA_SPEED, speedMps)
                .putExtra(EXTRA_VARIATION, variation)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_STOPS, stops)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RoutePlaybackService::class.java).setAction(ACTION_STOP))
        }

        fun togglePause(context: Context) {
            context.startService(Intent(context, RoutePlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun skip(context: Context) {
            context.startService(Intent(context, RoutePlaybackService::class.java).setAction(ACTION_SKIP))
        }
    }
}
