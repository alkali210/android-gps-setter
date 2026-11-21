package io.github.jqssun.gpssetter.xposed

import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.jqssun.gpssetter.BuildConfig
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal class SystemFixCadence {
    private var lastAttemptMs = 0L

    fun nextFixMs(lastGpsReportMs: Long, settings: HookSettings): Long =
        maxOf(lastGpsReportMs, lastAttemptMs) + settings.locationUpdateIntervalMs

    fun generateIfDue(nowMs: Long, lastGpsReportMs: Long, settings: HookSettings, generate: () -> Unit) {
        if (nowMs >= nextFixMs(lastGpsReportMs, settings)) {
            lastAttemptMs = nowMs
            generate()
        }
    }
}

object LocationHook {

    private const val TAG = "GPS Setter"
    @Volatile private var current = HookSettings()
    private var rawSettings = current
    private val randomPosition = RandomPosition()
    private lateinit var module: XposedModule
    private lateinit var settings: Xshare
    private var systemProcess = false
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    // Cached provider managers (e.g. "gps", "fused", "network") for generating fake fixes in system_server
    private val providerManagers = ConcurrentHashMap<String, Any>()
    private var systemWorkerStarted = false
    @Volatile private var generateFixes: (() -> Unit)? = null
    @Volatile private var lastGpsReportTime: Long = 0
    private var locationResultWrap: Method? = null

    private fun init(xposed: XposedModule, system: Boolean = false) {
        module = xposed
        val prefs = xposed.getRemotePreferences(Xshare.GROUP)
        systemProcess = system
        settings = if (system) Xshare(prefs) {
            ParcelFileDescriptor.AutoCloseInputStream(xposed.openRemoteFile(Xshare.FILE))
        } else Xshare(prefs)
        rawSettings = (if (system) settings.refresh(SystemClock.elapsedRealtime()) else settings.initialSettings) ?: HookSettings()
        current = rawSettings
    }

    // System callbacks use the worker's in-memory fix; never open files on framework threads.
    private fun isActive(interval: Int): Boolean {
        if (!systemProcess) {
            val now = SystemClock.elapsedRealtime()
            if (now >= settings.nextReadMs) updateLocation(now, interval.toLong())
        }
        return current.isStarted
    }

    @Synchronized
    private fun updateLocation(nowMs: Long, maxIntervalMs: Long = Long.MAX_VALUE) {
        try {
            val refreshed = settings.refresh(nowMs, maxIntervalMs)
            if (refreshed == null && nowMs < randomPosition.nextSampleMs) return
            val next = refreshed ?: rawSettings
            if (next.isStarted != current.isStarted) module.log(Log.INFO, TAG, "Spoofing ${if (next.isStarted) "on" else "off"}")
            rawSettings = next
            current = randomPosition.apply(next, nowMs, minOf(next.locationUpdateIntervalMs, maxIntervalMs))
        } catch (e: Exception) {
            module.log(Log.ERROR, TAG, "Failed to read settings", e)
        }
    }

    // the spoofed fix; keeps timing/bearing from the real one when there is one
    private fun fakeLocation(origin: Location? = null, provider: String = LocationManager.GPS_PROVIDER): Location {
        val fix = current
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
        location.latitude = if (fix.latitude == 0.0 && fix.longitude == 0.0) 0.000001 else fix.latitude
        location.longitude = if (fix.latitude == 0.0 && fix.longitude == 0.0) 0.000001 else fix.longitude
        // LocationResult.validate() rejects accuracy <=0 or > 1,000,000 m; keep it sane
        location.accuracy = fix.accuracy.coerceIn(1.0f, 10000.0f)
        location.altitude = fix.altitude
        location.speed = if (fix.speed > 0f) fix.speed else 0F
        location.speedAccuracyMetersPerSecond = if (fix.speed > 0f) 1F else 0F
        // route playback supplies a heading; otherwise keep the real fix's bearing (set above)
        if (fix.bearing >= 0f) {
            location.bearing = fix.bearing
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
    private fun configureFixGenerator(classLoader: ClassLoader, lmsClass: Class<*>, lpmClass: Class<*>, emergencyField: Field?) {
        val targets = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val resultClass = Class.forName("android.location.LocationResult")
        val onReportLocation = lpmClass.getDeclaredMethod("onReportLocation", resultClass).apply {
            isAccessible = true
        }

        generateFixes = generate@{
            // Never generate fake fixes during an active emergency call/SMS.
            if (providerManagers.values.any { inEmergency(emergencyField?.get(it)) }) return@generate
            if (!providerManagers.containsKey(LocationManager.GPS_PROVIDER)) {
                tryResolveProviders(classLoader, lmsClass, lpmClass)
            }
            for (name in targets) {
                val manager = providerManagers[name] ?: continue
                try {
                    val fakeLoc = fakeLocation(provider = name)
                    val res = wrapLocationResult(resultClass, fakeLoc) ?: continue
                    onReportLocation.invoke(manager, res)
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "Failed to deliver fake fix to $name", t)
                }
            }
        }
    }

    // One worker services both deadlines: the file must still be checked with no requests,
    // no providers or spoofing off. A real GPS report postpones injection, not settings reads.
    private fun startSystemWorker() {
        if (systemWorkerStarted) return
        systemWorkerStarted = true
        Thread({
            val cadence = SystemFixCadence()
            while (true) {
                var sleepMs = Xshare.MAX_CONTROL_INTERVAL_MS
                try {
                    val now = SystemClock.elapsedRealtime()
                    val readOrSampleAt = minOf(settings.nextReadMs, randomPosition.nextSampleMs)
                    if (now >= readOrSampleAt) updateLocation(now)
                    val fix = current
                    var wakeAt = minOf(settings.nextReadMs, randomPosition.nextSampleMs)
                    val generate = generateFixes
                    if (generate != null && fix.isStarted) {
                        cadence.generateIfDue(now, lastGpsReportTime, fix, generate)
                        wakeAt = minOf(wakeAt, cadence.nextFixMs(lastGpsReportTime, fix))
                    }
                    sleepMs = (wakeAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "System location worker error", t)
                }
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "GpsSetter-SystemLocation").apply {
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
        init(xposed, system = true)
        module.log(Log.INFO, TAG, "Hooking system server (SDK ${Build.VERSION.SDK_INT})")
        if (!current.isHookedSystem) {
            module.log(Log.INFO, TAG, "System hook disabled in settings")
            return
        }
        // Keep settings live even on ROMs without the optional fix-generator methods.
        startSystemWorker()
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

                safely("fix generator") { configureFixGenerator(classLoader, lms, lpm, emergency) }
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
        if (systemProcess || packageName in ignorePkg) return
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
