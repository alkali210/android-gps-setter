package io.github.jqssun.gpssetter.route

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Pure geometry for route playback, so it's unit-tested.
object RouteMath {
    private const val EARTH_R = 6371000.0 // mean earth radius, metres

    data class Fix(val lat: Double, val lng: Double, val altitude: Double, val bearing: Float)

    fun haversineMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearingDeg(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val lat1 = Math.toRadians(aLat); val lat2 = Math.toRadians(bLat)
        val dLng = Math.toRadians(bLng - aLng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun pathLength(points: List<RoutePoint>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += haversineMeters(points[i].lat, points[i].lng, points[i + 1].lat, points[i + 1].lng)
        }
        return total
    }

    // Cumulative distance to the route vertex nearest (lat,lng) — how far along the route you are.
    fun distanceAlong(points: List<RoutePoint>, lat: Double, lng: Double): Double {
        if (points.isEmpty()) return 0.0
        var cum = 0.0; var best = 0.0; var bestD = Double.MAX_VALUE
        for (i in points.indices) {
            if (i > 0) cum += haversineMeters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
            val d = haversineMeters(points[i].lat, points[i].lng, lat, lng)
            if (d < bestD) { bestD = d; best = cum }
        }
        return best
    }

    // Position/heading/altitude at cumulative [distanceMeters] along the polyline.
    // Clamps to the first point at/below 0 and the last point at/beyond the end.
    fun pointAtDistance(points: List<RoutePoint>, distanceMeters: Double): Fix? {
        if (points.isEmpty()) return null
        if (points.size == 1 || distanceMeters <= 0.0) {
            val p = points.first()
            val b = if (points.size > 1) bearingDeg(points[0].lat, points[0].lng, points[1].lat, points[1].lng) else 0.0
            return Fix(p.lat, p.lng, p.altitude, b.toFloat())
        }
        var remaining = distanceMeters
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val seg = haversineMeters(a.lat, a.lng, b.lat, b.lng)
            if (seg <= 0.0) continue
            if (remaining <= seg) {
                val f = remaining / seg
                return Fix(
                    lat = a.lat + (b.lat - a.lat) * f,
                    lng = a.lng + (b.lng - a.lng) * f,
                    altitude = a.altitude + (b.altitude - a.altitude) * f,
                    bearing = bearingDeg(a.lat, a.lng, b.lat, b.lng).toFloat()
                )
            }
            remaining -= seg
        }
        // past the end: last point, heading of the final segment
        val last = points.last(); val prev = points[points.size - 2]
        return Fix(last.lat, last.lng, last.altitude, bearingDeg(prev.lat, prev.lng, last.lat, last.lng).toFloat())
    }

    // ---- playback ----

    const val ONCE = 0
    const val LOOP = 1
    const val PING_PONG = 2

    // dir is +1 going towards the end, -1 coming back (ping-pong only); done = a ONCE route reached its end
    data class Progress(val distance: Double, val dir: Int, val done: Boolean)

    // Move [step] metres from [distance] along a route [total] metres long.
    fun advance(distance: Double, dir: Int, step: Double, total: Double, mode: Int): Progress {
        if (total <= 0.0) return Progress(0.0, 1, true)
        val d = distance + dir * step
        return when {
            d >= total -> when (mode) {
                LOOP -> Progress(d % total, 1, false)
                PING_PONG -> Progress((2 * total - d).coerceAtLeast(0.0), -1, false) // bounce off the end
                else -> Progress(total, 1, true)
            }
            d <= 0.0 && dir < 0 -> Progress((-d).coerceAtMost(total), 1, false)   // bounce off the start
            else -> Progress(d.coerceAtLeast(0.0), dir, false)
        }
    }

    // Speed multiplier in [1 - variation, 1 + variation] (0.12 = ±12%) that drifts smoothly over
    // [tSeconds], like traffic. Two sines with unrelated periods; [phase] makes each run different.
    fun speedFactor(variation: Double, tSeconds: Double, phase: Double): Double =
        1 + variation * (0.6 * sin(2 * PI * tSeconds / 37 + phase) + 0.4 * sin(2 * PI * tSeconds / 11 + 2.3 * phase))

    // [cur] moved towards [target] by at most [maxStep]: speeding up and braking at a limited rate.
    fun approach(cur: Double, target: Double, maxStep: Double): Double =
        cur + (target - cur).coerceIn(-maxStep, maxStep)

    // Distance of the next stop ahead in the travel direction; the route's end (or start) if there's none.
    // 1 m of slack so standing on a stop skips to the one after it.
    fun nextStop(stops: DoubleArray, distance: Double, dir: Int, total: Double): Double =
        if (dir > 0) stops.filter { it > distance + 1 }.minOrNull() ?: total
        else stops.filter { it < distance - 1 }.maxOrNull() ?: 0.0

    // ---- slowing for corners ----

    // A turn at [at] metres along the route, of [turnDeg] degrees (0 = straight on, 180 = U-turn)
    data class Corner(val at: Double, val turnDeg: Double)

    // Turns sharper than [minTurn]: the heading [window] metres before each vertex against the
    // heading [window] metres after, so a curve drawn as many short segments counts as one turn.
    // Vertices within 2 windows of each other merge into one corner, the sharpest.
    fun corners(points: List<RoutePoint>, window: Double = 15.0, minTurn: Double = 20.0): List<Corner> {
        // duplicate points add no distance but would read as a 0° heading
        val pts = points.filterIndexed { i, p ->
            i == 0 || haversineMeters(points[i - 1].lat, points[i - 1].lng, p.lat, p.lng) > 0.5
        }
        if (pts.size < 3) return emptyList()
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + haversineMeters(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
        val heading = DoubleArray(pts.size - 1) { bearingDeg(pts[it].lat, pts[it].lng, pts[it + 1].lat, pts[it + 1].lng) }
        // heading of the segment that contains distance x
        fun headingAt(x: Double): Double {
            val i = cum.binarySearch(x).let { if (it >= 0) it else -it - 2 }
            return heading[i.coerceIn(0, heading.size - 1)]
        }
        val out = mutableListOf<Corner>()
        for (k in 1 until pts.size - 1) {
            val d = cum[k]
            val turn = angleBetween(headingAt(d - window), headingAt(d + window))
            if (turn < minTurn) continue
            val last = out.lastOrNull()
            if (last != null && d - last.at < 2 * window) {
                if (turn > last.turnDeg) out[out.lastIndex] = Corner(d, turn)
            } else {
                out += Corner(d, turn)
            }
        }
        return out
    }

    // 0..180 degrees between two headings
    fun angleBetween(a: Double, b: Double): Double = abs(((b - a + 540.0) % 360.0) - 180.0)

    // ponytail: fixed caps tuned by feel for a car; walking and most cycling stay under them, as in
    // life. Make them depend on the chosen speed if a "bike" or "truck" profile ever needs its own.
    private val CAP_TURN = doubleArrayOf(20.0, 45.0, 90.0, 135.0, 180.0)
    private val CAP_KMH = doubleArrayOf(60.0, 35.0, 20.0, 12.0, 8.0)

    // Fastest a turn of [turnDeg] is taken, in m/s; no limit for gentler turns than 20°.
    fun cornerCapMps(turnDeg: Double): Double {
        if (turnDeg < CAP_TURN[0]) return Double.POSITIVE_INFINITY
        for (i in 1 until CAP_TURN.size) {
            if (turnDeg <= CAP_TURN[i]) {
                val f = (turnDeg - CAP_TURN[i - 1]) / (CAP_TURN[i] - CAP_TURN[i - 1])
                return (CAP_KMH[i - 1] + (CAP_KMH[i] - CAP_KMH[i - 1]) * f) / 3.6
            }
        }
        return CAP_KMH.last() / 3.6
    }

    // Fastest speed allowed at [distance] going [dir], braking at [brake] m/s² so every corner ahead
    // is reached at no more than its cap. A corner stays in force for 5 m past it (you're mid-turn).
    fun cornerLimit(corners: List<Corner>, distance: Double, dir: Int, brake: Double): Double {
        var limit = Double.POSITIVE_INFINITY
        for (c in corners) {
            val ahead = (c.at - distance) * dir
            if (ahead < -5.0) continue
            val cap = cornerCapMps(c.turnDeg)
            limit = min(limit, sqrt(cap * cap + 2 * brake * max(ahead, 0.0)))
        }
        return limit
    }

    // Up to [max] + 1 points of the route scaled into a 0..1 box, x east and y south (screen order),
    // aspect kept and centred: the shape for a list thumbnail. Pairs: x0, y0, x1, y1, ...
    fun sketch(points: List<RoutePoint>, max: Int = 64): FloatArray {
        if (points.size < 2) return FloatArray(0)
        val step = ((points.size + max - 1) / max).coerceAtLeast(1)
        val picked = points.filterIndexed { i, _ -> i % step == 0 }
            .let { if (it.last() === points.last()) it else it + points.last() }
        val k = cos(Math.toRadians(points[0].lat)) // a degree of longitude is shorter away from the equator
        val xs = picked.map { it.lng * k }
        val ys = picked.map { -it.lat }
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        val span = max(w, h).takeIf { it > 0 } ?: 1.0
        val ox = xs.min() - (span - w) / 2
        val oy = ys.min() - (span - h) / 2
        return FloatArray(picked.size * 2) { i ->
            (if (i % 2 == 0) (xs[i / 2] - ox) / span else (ys[i / 2] - oy) / span).toFloat()
        }
    }

    // "850 m", "4.6 km"
    fun formatDistance(meters: Double): String =
        if (meters >= 1000) String.format("%.1f km", meters / 1000) else "${meters.roundToInt()} m"

    // "045° NE"; a negative bearing (unknown) gives null
    fun compass(bearing: Float): String? {
        if (bearing < 0f) return null
        val b = ((bearing.roundToInt() % 360) + 360) % 360
        val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return String.format(Locale.US, "%03d° %s", b, points[((b + 22.5) / 45).toInt() % 8])
    }
}
