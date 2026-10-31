package io.github.jqssun.gpssetter.xposed

import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.jqssun.gpssetter.BuildConfig
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LocationHook {

    private const val TAG = "GPS Setter"
    @Volatile var newlat: Double = 45.0000
    @Volatile var newlng: Double = 0.0000
    @Volatile private var accuracy: Float = 10.0f
    // motion for route playback; defaults mean static (no speed, bearing from the real fix)
    @Volatile private var newAlt: Double = 0.0
    @Volatile private var newSpeed: Float = 0f
    @Volatile private var newBearing: Float = -1f
    private val rand: Random = Random()
    private lateinit var module: XposedModule
    private lateinit var settings: Xshare
    @Volatile private var mLastUpdated: Long = 0
    @Volatile private var started = false
    @Volatile private var updateIntervalMs = LocationUpdateInterval.DEFAULT_MS
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    // Cached provider managers (e.g. "gps", "fused", "network") for generating fake fixes in system_server
    private val providerManagers = ConcurrentHashMap<String, Any>()
    @Volatile private var generatorStarted = false
    @Volatile private var lastGpsReportTime: Long = 0
    private var locationResultWrap: Method? = null

    private fun init(xposed: XposedModule) {
        module = xposed
        settings = Xshare(xposed.getRemotePreferences(Xshare.GROUP))
    }

    // re-reads settings (and re-rolls the random offset) at most once per interval, so start/stop and new locations apply without a reboot
    private fun isActive(interval: Int): Boolean {
        if (System.currentTimeMillis() - mLastUpdated >= minOf(interval.toLong(), updateIntervalMs)) {
            updateLocation()
        }
        return started
    }

    @Synchronized
    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            updateIntervalMs = settings.locationUpdateIntervalMs
            val now = settings.isStarted
            if (now != started) module.log(Log.INFO, TAG, "Spoofing ${if (now) "on" else "off"}")
            started = now
            if (!started) return
            val north = (rand.nextInt(51) - 25).toDouble()
            val east = (rand.nextInt(51) - 25).toDouble()
            newlat =
                if (settings.isRandomPosition) settings.getLat + GeoOffset.latOffsetDegrees(north) else settings.getLat
            newlng =
                if (settings.isRandomPosition) settings.getLng + GeoOffset.lngOffsetDegrees(east, settings.getLat) else settings.getLng
            accuracy = settings.accuracy?.toFloatOrNull()?.takeIf { it > 0f } ?: 10.0f
            newAlt = settings.altitude
            newSpeed = settings.speed
            newBearing = settings.bearing

        } catch (e: Exception) {
            module.log(Log.ERROR, TAG, "Failed to read settings", e)
        }
    }

    // the spoofed fix; keeps timing/bearing from the real one when there is one
    private fun fakeLocation(origin: Location? = null, provider: String = LocationManager.GPS_PROVIDER): Location {
        val location = Location(origin?.provider ?: provider)
        if (origin != null) {
            location.time = origin.time
            location.bearing = origin.bearing
            location.bearingAccuracyDegrees = origin.bearingAccuracyDegrees
            location.elapsedRealtimeNanos = origin.elapsedRealtimeNanos
            location.verticalAccuracyMeters = origin.verticalAccuracyMeters
        } else {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            location.time = System.currentTimeMillis() - 300
            location.elapsedRealtimeNanos = nowNanos - 300_000_000L
        }
        location.latitude = if (newlat == 0.0 && newlng == 0.0) 0.000001 else newlat
        location.longitude = if (newlat == 0.0 && newlng == 0.0) 0.000001 else newlng
        // LocationResult.validate() rejects accuracy <=0 or > 1,000,000 m; keep it sane
        location.accuracy = accuracy.coerceIn(1.0f, 10000.0f)
        location.altitude = newAlt
        location.speed = if (newSpeed > 0f) newSpeed else 0F
        location.speedAccuracyMetersPerSecond = if (newSpeed > 0f) 1F else 0F
        // route playback supplies a heading; otherwise keep the real fix's bearing (set above)
        if (newBearing >= 0f) {
            location.bearing = newBearing
            location.bearingAccuracyDegrees = 1F
        }
        try {
            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "unable to set mock $e")
        }
        return location
    }

    private fun wrapLocationResult(locationResultClass: Class<*>, location: Location): Any? {
        if (locationResultWrap == null) {
            locationResultWrap = try {
                locationResultClass.getMethod("wrap", List::class.java)
            } catch (e: NoSuchMethodException) {
                try {
                    locationResultClass.getMethod("create", List::class.java)
                } catch (e2: NoSuchMethodException) {
                    locationResultClass.getMethod("wrap", Array<Location>::class.java)
                }
            }
        }
        val wrap = locationResultWrap ?: return null
        return if (wrap.parameterTypes[0] == List::class.java) {
            wrap.invoke(null, listOf(location))
        } else {
            wrap.invoke(null, arrayOf(location))
        }
    }

    private fun registerProviderManager(manager: Any, lpmClass: Class<*>) {
        if (!lpmClass.isInstance(manager)) return
        try {
            val getName = lpmClass.getMethod("getName")
            val name = getName.invoke(manager) as? String ?: return
            if (providerManagers.putIfAbsent(name, manager) == null) {
                module.log(Log.INFO, TAG, "Discovered location provider: $name")
            }
        } catch (_: Throwable) {}
    }

    private fun scanLms(instance: Any, lmsClass: Class<*>, lpmClass: Class<*>) {
        try {
            try {
                val getLpm = lmsClass.getDeclaredMethod("getLocationProviderManager", String::class.java).apply {
                    isAccessible = true
                }
                for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    val mgr = getLpm.invoke(instance, name)
                    if (mgr != null) registerProviderManager(mgr, lpmClass)
                }
            } catch (_: Throwable) {}

            for (field in lmsClass.declaredFields) {
                if (Iterable::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val list = field.get(instance) as? Iterable<*> ?: continue
                    for (item in list) {
                        if (item != null && lpmClass.isInstance(item)) {
                            registerProviderManager(item, lpmClass)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Failed scanning LMS fields", t)
        }
    }

    private fun tryResolveProviders(classLoader: ClassLoader, lmsClass: Class<*>, lpmClass: Class<*>) {
        try {
            val smClass = classLoader.loadClass("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "location") ?: return
            if (lmsClass.isInstance(binder)) {
                scanLms(binder, lmsClass, lpmClass)
            }
        } catch (_: Throwable) {}
    }

    // Supply missing provider fixes at the same cadence as the app's coordinate updates.
    private fun startFixGenerator(classLoader: ClassLoader, lmsClass: Class<*>, lpmClass: Class<*>, emergencyField: Field?) {
        if (generatorStarted) return
        generatorStarted = true

        val resultClass = Class.forName("android.location.LocationResult")
        val onReportLocation = lpmClass.getDeclaredMethod("onReportLocation", resultClass).apply {
            isAccessible = true
        }

        Thread({
            module.log(Log.INFO, TAG, "Started fake fix generator loop")
            var lastAttempt = 0L
            val targets = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            while (true) {
                try {
                    val cadence = updateIntervalMs
                    val elapsed = SystemClock.elapsedRealtime() - maxOf(lastGpsReportTime, lastAttempt)
                    Thread.sleep(if (started) (cadence - elapsed).coerceIn(1L, 250L) else 250L)
                    if (!isActive(200)) continue

                    val now = SystemClock.elapsedRealtime()
                    if (now - maxOf(lastGpsReportTime, lastAttempt) < updateIntervalMs) continue
                    lastAttempt = now

                    // Never generate fake fixes during an active emergency call/SMS
                    val inEmerg = providerManagers.values.any { inEmergency(emergencyField?.get(it)) }
                    if (inEmerg) continue

                    if (!providerManagers.containsKey(LocationManager.GPS_PROVIDER)) {
                        tryResolveProviders(classLoader, lmsClass, lpmClass)
                    }

                    var deliveredGps = false
                    for (name in targets) {
                        val manager = providerManagers[name] ?: continue
                        try {
                            val fakeLoc = fakeLocation(provider = name)
                            val res = wrapLocationResult(resultClass, fakeLoc) ?: continue
                            // Use the provider's normal delivery path, including its listener filtering.
                            onReportLocation.invoke(manager, res)
                            if (name == LocationManager.GPS_PROVIDER) deliveredGps = true
                        } catch (t: Throwable) {
                            module.log(Log.WARN, TAG, "Failed to deliver fake fix to $name", t)
                        }
                    }

                    if (deliveredGps) {
                        lastGpsReportTime = now
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "Fix generator tick error", t)
                }
            }
        }, "GpsSetter-FixGenerator").apply {
            isDaemon = true
            start()
        }
    }

    // for methods taking a single Location: hand the original our fake instead
    private fun proceedWithFake(chain: Chain): Any? =
        chain.proceed(arrayOf<Any?>(fakeLocation(chain.getArg(0) as Location?)))

    // one missing method (ROM / version differences) shouldn't take the other hooks down with it
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
            module.log(Log.INFO, TAG, "Hooked $what")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "Skipping hook $what", t)
        }
    }

    fun initSystemHooks(xposed: XposedModule, classLoader: ClassLoader) {
        init(xposed)
        module.log(Log.INFO, TAG, "Hooking system server (SDK ${Build.VERSION.SDK_INT})")
        if (!settings.isHookedSystem) {
            module.log(Log.INFO, TAG, "System hook disabled in settings")
            return
        }
        val interval = 200

        // LocationManagerService lived in com.android.server up to Android 10, com.android.server.location from 11
        if (Build.VERSION.SDK_INT < 30) {
            val lms = classLoader.loadClass("com.android.server.LocationManagerService")

            safely("getLastLocation") {
                module.hook(lms.getDeclaredMethod("getLastLocation", LocationRequest::class.java, String::class.java))
                    .intercept { chain -> if (isActive(interval)) fakeLocation() else chain.proceed() }
            }

            for (method in lms.declaredMethods) {
                if (method.returnType == Boolean::class.java &&
                    method.name in listOf("addGnssBatchingCallback", "addGnssMeasurementsListener", "addGnssNavigationMessageListener")
                ) {
                    safely(method.name) {
                        module.hook(method).intercept { chain -> if (isActive(interval)) false else chain.proceed() }
                    }
                }
            }

            safely("callLocationChangedLocked") {
                val receiver = classLoader.loadClass("com.android.server.LocationManagerService\$Receiver")
                module.hook(receiver.getDeclaredMethod("callLocationChangedLocked", Location::class.java))
                    .intercept { chain -> if (isActive(interval)) proceedWithFake(chain) else chain.proceed() }
            }
        } else {
            val lms = classLoader.loadClass("com.android.server.location.LocationManagerService")

            for (method in lms.declaredMethods) {
                if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                    // params: String::class.java, LastLocationRequest::class.java, String::class.java, String::class.java
                    safely(method.name) {
                        module.hook(method).intercept { chain -> if (isActive(interval)) fakeLocation() else chain.proceed() }
                    }
                } else if (method.name in listOf("addGnssBatchingCallback", "startGnssBatch", "addGnssAntennaInfoListener", "addGnssMeasurementsListener", "addGnssNavigationMessageListener")) {
                    // refuse raw GNSS data; these return boolean on Android 11 and void from 12
                    val refused: Any? = if (method.returnType == Boolean::class.java) false else null
                    safely(method.name) {
                        module.hook(method).intercept { chain -> if (isActive(interval)) refused else chain.proceed() }
                    }
                }
            }

            safely("injectLocation") {
                module.hook(lms.getDeclaredMethod("injectLocation", Location::class.java))
                    .intercept { chain -> if (isActive(interval)) proceedWithFake(chain) else chain.proceed() }
            }

            // Android 12+: every provider's fix (gps, network, fused, passive) passes through here on
            // its way to the last-location cache and to every app listening for updates
            if (Build.VERSION.SDK_INT >= 31) {
                val lpm = classLoader.loadClass("com.android.server.location.provider.LocationProviderManager")
                val result = Class.forName("android.location.LocationResult") // @SystemApi, not in the public SDK
                val size = result.getMethod("size")
                val get = result.getMethod("get", Int::class.java)
                val getName = lpm.getMethod("getName")
                // Android 14+ only; on 12/13 there is no emergency state here to check
                val emergency = try { lpm.getDeclaredField("mEmergencyHelper").apply { isAccessible = true } } catch (e: NoSuchFieldException) { null }

                safely("onReportLocation") {
                    module.hook(lpm.getDeclaredMethod("onReportLocation", result)).intercept { chain ->
                        val manager = chain.thisObject
                        registerProviderManager(manager, lpm)
                        val name = try { getName.invoke(manager) as? String } catch (_: Throwable) { null }
                        if (name == LocationManager.GPS_PROVIDER) {
                            lastGpsReportTime = SystemClock.elapsedRealtime()
                        }
                        if (isActive(interval) && !inEmergency(emergency?.get(manager))) {
                            val locations = chain.getArg(0)
                            for (i in 0 until size.invoke(locations) as Int) {
                                val location = get.invoke(locations, i) as Location
                                location.set(fakeLocation(location))
                            }
                        }
                        chain.proceed()
                    }
                }

                for (method in lms.declaredMethods) {
                    if (method.name == "addLocationProviderManager" && method.parameterTypes.isNotEmpty()) {
                        safely("addLocationProviderManager") {
                            module.hook(method).intercept { chain ->
                                val mgr = chain.getArg(0)
                                if (mgr != null) registerProviderManager(mgr, lpm)
                                chain.proceed()
                            }
                        }
                    } else if (method.name in listOf("onSystemReady", "onSystemThirdPartyAppsCanStart")) {
                        safely(method.name) {
                            module.hook(method).intercept { chain ->
                                scanLms(chain.thisObject, lms, lpm)
                                chain.proceed()
                            }
                        }
                    }
                }

                startFixGenerator(classLoader, lms, lpm, emergency)
            }
        }
    }


    // never hand emergency services a fake fix: during an emergency call/SMS real locations go through untouched
    private fun inEmergency(helper: Any?): Boolean = helper != null && try {
        helper.javaClass.getMethod("isInEmergency", Long::class.java).invoke(helper, 0L) as Boolean
    } catch (t: Throwable) {
        false
    }

    fun initAppHooks(xposed: XposedModule, packageName: String) {
        if (packageName in ignorePkg) return
        init(xposed)
        val interval = 80 // 200

        // Android Location fixes are WGS-84. Inject at the framework delivery boundary,
        // never in Location getters/set(): SDKs also use Location to hold and copy
        // converted GCJ-02/BD-09 coordinates, which must remain untouched.
        val listenerTransport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "android.location.LocationManager\$LocationListenerTransport"
        } else {
            "android.location.LocationManager\$ListenerTransport"
        }
        hookLocationTransport(listenerTransport, interval)
        if (Build.VERSION.SDK_INT >= 30) {
            hookLocationTransport("android.location.LocationManager\$GetCurrentLocationTransport", interval)
        }

        safely("getLastKnownLocation") {
            module.hook(LocationManager::class.java.getDeclaredMethod("getLastKnownLocation", String::class.java))
                .intercept { chain ->
                    if (isActive(interval)) fakeLocation(provider = chain.getArg(0) as String) else chain.proceed()
                }
        }
    }

    private fun hookLocationTransport(className: String, interval: Int) {
        safely(className) {
            val transport = Class.forName(className)
            for (method in transport.declaredMethods) {
                if (method.name !in listOf("onLocationChanged", "onLocation")) continue
                val firstType = method.parameterTypes.firstOrNull() ?: continue
                if (firstType != Location::class.java && firstType != List::class.java) continue
                module.hook(method).intercept { chain ->
                    if (!isActive(interval) || chain.getArg(0) == null) {
                        chain.proceed()
                    } else {
                        val args = Array<Any?>(method.parameterCount) { chain.getArg(it) }
                        args[0] = when (val fix = args[0]) {
                            is Location -> fakeLocation(fix)
                            is List<*> -> fix.map { fakeLocation(it as Location) }
                            else -> args[0]
                        }
                        // Keep completion callbacks and other framework arguments intact.
                        chain.proceed(args)
                    }
                }
            }
        }
    }
}
