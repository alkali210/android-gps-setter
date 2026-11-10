package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.FavListAdapter
import io.github.jqssun.gpssetter.route.RouteMath
import io.github.jqssun.gpssetter.route.RoutePlaybackService
import io.github.jqssun.gpssetter.route.RoutePoint
import io.github.jqssun.gpssetter.route.Routing
import io.github.jqssun.gpssetter.route.parseGpx
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.jqssun.gpssetter.databinding.ActivityMapBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.StopSpoofReceiver
import io.github.jqssun.gpssetter.utils.applyAccent
import io.github.jqssun.gpssetter.utils.ext.*
import io.github.jqssun.gpssetter.xposed.GeoOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

    // The selected point (marker). Starts at the last spoofed point; the Teleport panel follows it.
    protected var lat by Delegates.observable(PrefManager.getLat) { _, _, _ -> renderTeleport() }
    protected var lon by Delegates.observable(PrefManager.getLng) { _, _, _ -> renderTeleport() }
    protected val viewModel by viewModels<MainViewModel>()
    protected val binding by lazy { ActivityMapBinding.inflate(layoutInflater) }
    protected lateinit var alertDialog: MaterialAlertDialogBuilder
    protected lateinit var dialog: AlertDialog
    protected val update by lazy { viewModel.getAvailableUpdate() }

    private val notificationsChannel by lazy { NotificationsChannel() }
    private var favListAdapter: FavListAdapter = FavListAdapter()
    private var xposedDialog: AlertDialog? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val PERMISSION_ID = 42

    // SAF pickers; registered as fields per the AndroidX ActivityResult contract
    private val exportFavoritesLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val json = viewModel.favoritesAsJson()
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    }
                    showToast(getString(R.string.favorites_exported))
                } catch (e: Exception) {
                    showToast(getString(R.string.export_failed))
                }
            }
        }
    private val importFavoritesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (text != null) viewModel.importFavorites(text)
                else showToast(getString(R.string.favorites_import_failed))
            }
        }
    private val gpxLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                val route = text?.let { parseGpx(it) }.orEmpty()
                if (route.size < 2) showToast(getString(R.string.route_empty)) else askSpeedAndPlay(route)
            }
        }

    private fun askSpeedAndPlay(route: List<io.github.jqssun.gpssetter.route.RoutePoint>) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("50")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_speed_title)
            .setView(input)
            .setPositiveButton(R.string.play_route) { _, _ ->
                val kmh = input.text.toString().toFloatOrNull()?.takeIf { it > 0f } ?: 50f
                drawRoute(route.map { it.lat to it.lng })
                RoutePlaybackService.start(this, route, kmh / 3.6f, loop = false)
                showToast(getString(R.string.route_started))
            }
            .show()
    }

    // Effective night mode (system or the in-app Dark theme setting); picks the map style.
    protected val isNight get() =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)
    // Make (la, ln) the selected point: marker there, camera pans (zoom kept), lat/lon set. No-op until the map is ready.
    protected abstract fun selectPoint(la: Double, ln: Double)
    // Applies the Settings "Map" type (PrefManager.mapType); no-op until the map is ready. Called from onMapReady and onResume (#12).
    protected abstract fun applyMapType()

    // Route overlay (flavor-specific map): polyline + A/B markers, and a moving traveler dot.
    protected abstract fun drawRoute(points: List<Pair<Double, Double>>)
    protected abstract fun clearRoute()
    protected abstract fun setTraveler(lat: Double, lng: Double)

    // A circular marker bitmap (filled dot with a white ring) for A/B/traveler markers.
    protected fun dotBitmap(color: Int): android.graphics.Bitmap {
        val d = resources.displayMetrics.density
        val size = (20 * d).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val r = size / 2f
        val ring = 2f * d
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = color
        canvas.drawCircle(r, r, r - ring, p)
        p.color = android.graphics.Color.WHITE
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = ring
        canvas.drawCircle(r, r, r - ring, p)
        return bmp
    }

    // ---- Route mode (Static / Route toggle, A->B directions, playback) ----
    private var routeMode = false
    private var aPoint: Pair<Double, Double>? = null
    private var bPoint: Pair<Double, Double>? = null
    private var aName = ""
    private var bName = ""
    private var roadPoints: List<RoutePoint>? = null
    private var totalMeters = 0.0
    private var speedKmh = 50
    private val travelerHandler = Handler(Looper.getMainLooper())
    private val travelerTick = object : Runnable {
        override fun run() {
            val lat = PrefManager.getLat; val lng = PrefManager.getLng
            setTraveler(lat, lng)
            updateTravelStats(lat, lng)
            travelerHandler.postDelayed(this, 1000)
        }
    }

    // ---- Bottom nav: Teleport/Routes switch the map's mode; Saved/Targets cover the map ----
    private val backToTeleport = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { binding.bottomNav.selectedItemId = R.id.tab_teleport }
    }

    private fun setupTabs() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val id = item.itemId
            binding.screenSaved.root.isVisible = id == R.id.tab_saved
            binding.screenTargets.root.isVisible = id == R.id.tab_targets
            when (id) {
                R.id.tab_teleport -> setRouteMode(false)
                R.id.tab_routes -> setRouteMode(true)
            }
            backToTeleport.isEnabled = id != R.id.tab_teleport
            true
        }
        onBackPressedDispatcher.addCallback(this, backToTeleport)
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, ActivitySettings::class.java)) }

        // edge-to-edge: keep the search bar and the full-screen tabs below the status bar
        val pad = resources.getDimensionPixelSize(R.dimen.padding_8)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            binding.topBar.updatePadding(top = top + pad)
            binding.screenSaved.root.updatePadding(top = top)
            binding.screenTargets.root.updatePadding(top = top)
            insets
        }
    }

    private fun setRouteMode(on: Boolean) {
        if (on == routeMode) return // e.g. Routes -> Saved -> Routes keeps a route in progress
        routeMode = on
        binding.routePanel.isVisible = on
        binding.teleportPanel.isVisible = !on
        binding.dpad.isVisible = !on
        if (on) showSetup() else resetRoute()
    }

    // ---- Teleport tab: telemetry, jitter, D-pad; start/stop follow the saved spoof state ----
    private val nudgeSteps = intArrayOf(1, 5, 10)
    private var nudgeStep = 1 // index into nudgeSteps: 5 m

    private fun setupTeleport() {
        val b = binding
        b.nudgeStep.text = getString(R.string.meters_fmt, nudgeSteps[nudgeStep])
        b.nudgeStep.setOnClickListener {
            nudgeStep = (nudgeStep + 1) % nudgeSteps.size
            b.nudgeStep.text = getString(R.string.meters_fmt, nudgeSteps[nudgeStep])
        }
        b.nudgeN.setOnClickListener { nudge(1.0, 0.0) }
        b.nudgeS.setOnClickListener { nudge(-1.0, 0.0) }
        b.nudgeE.setOnClickListener { nudge(0.0, 1.0) }
        b.nudgeW.setOnClickListener { nudge(0.0, -1.0) }

        // the slider only accepts multiples of its 0.5 m step
        b.jitterSlider.value = (PrefManager.jitterMeters * 2).roundToInt().coerceIn(0, 50) / 2f
        showJitter(b.jitterSlider.value)
        b.jitterSlider.addOnChangeListener { _, v, fromUser ->
            showJitter(v)
            if (fromUser) PrefManager.jitterMeters = v
        }

        b.copyCoords.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_coords), coordText()))
            if (Build.VERSION.SDK_INT < 33) showToast(getString(R.string.copied)) // 13+ shows its own confirmation
        }

        // start/stop can also change from the QS tile, the notification or route playback
        PrefManager.changed.observe(this) { renderTeleport() }
        renderTeleport()
    }

    private fun coordText() = String.format(Locale.US, "%.6f, %.6f", lat, lon)

    private fun showJitter(v: Float) {
        binding.jitterValue.text = if (v == 0f) getString(R.string.jitter_off) else String.format(Locale.US, "±%.1f m", v)
    }

    private fun nudge(north: Double, east: Double) {
        val m = nudgeSteps[nudgeStep]
        selectPoint(lat + GeoOffset.latOffsetDegrees(north * m), lon + GeoOffset.lngOffsetDegrees(east * m, lat))
        if (PrefManager.isStarted) viewModel.update(true, lat, lon) // a live spoof follows the D-pad
    }

    // Idle: "Teleport here". Live at the selected point: Stop. Live elsewhere: "Move here" + Stop.
    private fun renderTeleport() {
        val live = PrefManager.isStarted
        val here = live && abs(PrefManager.getLat - lat) < 1e-7 && abs(PrefManager.getLng - lon) < 1e-7
        val b = binding
        b.coords.text = coordText()
        b.spoofStatus.text = getString(if (live) R.string.spoof_live else R.string.spoof_idle, PrefManager.accuracy ?: "10")
        b.spoofDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (live) R.color.status_ok else R.color.glass_ink_dim)
        )
        b.startButton.isVisible = !here
        b.startButton.setText(if (live) R.string.move_here else R.string.start_mock)
        b.stopButton.isVisible = live
    }

    private fun setupSaved() {
        val saved = binding.screenSaved
        saved.favoritesList.layoutManager = LinearLayoutManager(this)
        saved.favoritesList.adapter = favListAdapter
        favListAdapter.onItemClick = { fav ->
            lat = fav.lat!!
            lon = fav.lng!!
            binding.bottomNav.selectedItemId = R.id.tab_teleport
            moveMapToNewLocation(true)
        }
        favListAdapter.onItemDelete = { viewModel.deleteFavorite(it) }
        saved.savedExport.setOnClickListener { exportFavoritesLauncher.launch("favorites.json") }
        saved.savedImport.setOnClickListener { importFavoritesLauncher.launch(arrayOf("application/json")) }
        viewModel.doGetUserDetails()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                    saved.savedEmpty.isVisible = it.isEmpty()
                }
            }
        }
    }

    private fun showModuleStatus(active: Boolean) {
        val t = binding.screenTargets
        t.moduleStatus.setText(if (active) R.string.module_active else R.string.error_xposed_module_missing)
        t.moduleDetail.text = if (active) PrefManager.framework.orEmpty() else getString(R.string.error_xposed_module_missing_desc)
        t.moduleDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (active) R.color.status_ok else R.color.status_bad)
        )
    }

    private fun setupRouteMode() {
        updateSpeedLabel()
        binding.routeGpx.setOnClickListener {
            if (checkPermissions()) gpxLauncher.launch(arrayOf("*/*")) else requestPermissions()
        }
        binding.speedMinus.setOnClickListener { speedKmh = (speedKmh - 10).coerceAtLeast(5); updateSpeedLabel() }
        binding.speedPlus.setOnClickListener { speedKmh = (speedKmh + 10).coerceAtMost(300); updateSpeedLabel() }
        binding.routeStart.setOnClickListener { startRoutePlayback() }
        binding.routeStop.setOnClickListener { stopRoutePlayback() }
        binding.routeCancel.setOnClickListener { resetRoute() }
        binding.routeSwap.setOnClickListener { swapAB() }
        binding.travelExpand.setOnClickListener {
            val shown = binding.travelDetails.visibility != View.VISIBLE
            binding.travelDetails.visibility = if (shown) View.VISIBLE else View.GONE
            binding.travelExpand.rotation = if (shown) 90f else 270f
        }
    }

    private fun showSetup() { binding.routeSetup.visibility = View.VISIBLE; binding.routeTravel.visibility = View.GONE }
    private fun showTravel() { binding.routeSetup.visibility = View.GONE; binding.routeTravel.visibility = View.VISIBLE }

    private fun updateSpeedLabel() { binding.speedValue.text = getString(R.string.route_speed_fmt, speedKmh) }

    // Flavors call this from onMapClick; returns true when route mode consumed the tap.
    protected fun onMapTapped(latTapped: Double, lngTapped: Double): Boolean {
        if (!routeMode) return false
        if (aPoint == null) {
            aPoint = latTapped to lngTapped
            setTraveler(latTapped, lngTapped)
            binding.routeAName.text = getString(R.string.route_locating)
            lifecycleScope.launch { aName = shortAddress(latTapped, lngTapped); binding.routeAName.text = aName }
        } else {
            bPoint = latTapped to lngTapped
            binding.routeBName.text = getString(R.string.route_locating)
            lifecycleScope.launch { bName = shortAddress(latTapped, lngTapped); binding.routeBName.text = bName }
            routeAtoB()
        }
        return true
    }

    private fun routeAtoB() {
        val a = aPoint ?: return
        val b = bPoint ?: return
        binding.routeStart.isEnabled = false
        binding.routeSummaryTv.visibility = View.VISIBLE
        binding.routeSummaryTv.text = getString(R.string.route_routing)
        lifecycleScope.launch {
            val road = Routing.road(RoutePoint(a.first, a.second), RoutePoint(b.first, b.second))
            if (road.size < 2) { binding.routeSummaryTv.text = getString(R.string.route_no_road); return@launch }
            roadPoints = road
            totalMeters = RouteMath.pathLength(road)
            drawRoute(road.map { it.lat to it.lng })
            val mins = (totalMeters / (speedKmh / 3.6) / 60.0).roundToInt().coerceAtLeast(1)
            binding.routeSummaryTv.text = getString(R.string.route_summary, formatDistance(totalMeters), "$mins min")
            binding.routeStart.isEnabled = true
        }
    }

    private fun swapAB() {
        val a = aPoint; val b = bPoint ?: return
        aPoint = b; bPoint = a
        val an = aName; aName = bName; bName = an
        binding.routeAName.text = aName
        binding.routeBName.text = bName
        routeAtoB()
    }

    private fun startRoutePlayback() {
        val road = roadPoints ?: return
        RoutePlaybackService.start(this, road, speedKmh / 3.6f, loop = false)
        binding.travelDetails.text = "A  $aName\nB  $bName"
        showTravel()
        travelerHandler.post(travelerTick)
    }

    private fun stopRoutePlayback() {
        RoutePlaybackService.stop(this)
        travelerHandler.removeCallbacks(travelerTick)
        showSetup()
    }

    private fun updateTravelStats(lat: Double, lng: Double) {
        val road = roadPoints ?: return
        val travelled = RouteMath.distanceAlong(road, lat, lng)
        binding.travelDist.text = formatDistance(travelled)
        val mps = PrefManager.speed
        binding.travelSpeed.text = getString(R.string.route_speed_fmt, (mps * 3.6).roundToInt())
        binding.travelProgress.setProgressCompat(
            if (totalMeters > 0) (travelled / totalMeters * 100).roundToInt().coerceIn(0, 100) else 0, true
        )
        val remaining = (totalMeters - travelled).coerceAtLeast(0.0)
        val eta = if (mps > 0.5f) (remaining / mps).roundToInt() else 0
        binding.travelEta.text = String.format("%d:%02d", eta / 60, eta % 60)
    }

    private fun resetRoute() {
        aPoint = null; bPoint = null; roadPoints = null; aName = ""; bName = ""; totalMeters = 0.0
        binding.routeAName.text = getString(R.string.route_pick_a)
        binding.routeBName.text = getString(R.string.route_b_placeholder)
        binding.routeSummaryTv.visibility = View.GONE
        binding.routeStart.isEnabled = false
        travelerHandler.removeCallbacks(travelerTick)
        showSetup()
        clearRoute()
    }

    private suspend fun shortAddress(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        val name = try {
            android.location.Geocoder(this@BaseMapActivity).getFromLocation(lat, lng, 1)?.firstOrNull()?.let {
                it.subLocality ?: it.locality ?: it.featureName ?: it.getAddressLine(0)
            }
        } catch (e: Exception) { null }
        name ?: String.format("%.5f, %.5f", lat, lng)
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) String.format("%.1f km", meters / 1000) else "${meters.roundToInt()} m"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAccent()
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launchWhenCreated {
            setContentView(binding.root)
        }
        initializeMap()
        checkModuleEnabled()
        checkUpdates()
        setupSearch()
        setupButtons()
        setupTabs()
        setupTeleport()
        setupSaved()
        setupRouteMode()
        // observe once; registering inside the Add dialog stacked an observer (and a toast) per save
        viewModel.response.observe(this) {
            showToast(getString(if (it == (-1).toLong()) R.string.cant_save else R.string.save))
        }
        viewModel.importResult.observe(this) { n ->
            showToast(if (n != null) getString(R.string.favorites_imported, n) else getString(R.string.favorites_import_failed))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_ID)
        }
        if (PrefManager.isJoystickEnabled){
            startService(Intent(this, JoystickService::class.java))
        }
    }

    private fun setupSearch() {
        val progress = binding.search.searchProgress
        binding.search.searchPaste.setOnClickListener {
            val text = getSystemService(ClipboardManager::class.java).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                showToast(getString(R.string.clipboard_empty))
            } else {
                binding.search.searchBox.setText(text)
                binding.search.searchBox.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            }
        }
        binding.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // "lat, lng" needs no geocoder, so it works offline
                if (isNetworkConnected() || COORDS.matcher(v.text.trim()).matches()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val getInput = v.text.toString().trim()
                        if (getInput.isNotEmpty()){
                            getSearchAddress(getInput).let {
                                it.collect { result ->
                                    when(result) {
                                        is SearchProgress.Progress -> {
                                            progress.visibility = View.VISIBLE
                                        }
                                        is SearchProgress.Complete -> {
                                            progress.visibility = View.GONE
                                            lat = result.lat
                                            lon = result.lon
                                            moveMapToNewLocation(true)
                                        }
                                        is SearchProgress.Fail -> {
                                            progress.visibility = View.GONE
                                            showToast(result.error!!)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    showToast(getString(R.string.no_internet))
                }
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

    }

    private fun checkModuleEnabled(){
        viewModel.isXposed.observe(this) { isXposed ->
            showModuleStatus(isXposed)
            xposedDialog?.dismiss()
            xposedDialog = null
            if (!isXposed) {
                xposedDialog = MaterialAlertDialogBuilder(this).run {
                    setTitle(R.string.error_xposed_module_missing)
                    setMessage(R.string.error_xposed_module_missing_desc)
                    // setCancelable(BuildConfig.DEBUG)
                    setCancelable(true)
                    show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateXposedState()
        applyMapType() // picks up a change made in Settings

    }

    protected fun addFavoriteDialog() {
        alertDialog =  MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog,null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            setTitle(getString(R.string.add_fav_dialog_title))
            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (!hasMarker()){
                  showToast(getString(R.string.location_not_select))
                }else{
                    viewModel.storeFavorite(s, lat, lon)
                }
            }
            setView(view)
            show()
        }
    }

    private fun checkUpdates(){
        lifecycleScope.launchWhenResumed {
            viewModel.update.collect{
                if (it!= null){
                    updateDialog()
                }
            }
        }
    }

    private fun updateDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(R.string.update_available)
        alertDialog.setMessage(update?.changelog)
        alertDialog.setPositiveButton(getString(R.string.update_button)) { _, _ ->
            MaterialAlertDialogBuilder(this).apply {
                val view = layoutInflater.inflate(R.layout.update_dialog, null)
                val progress = view.findViewById<LinearProgressIndicator>(R.id.update_download_progress)
                val cancel = view.findViewById<AppCompatButton>(R.id.update_download_cancel)
                setView(view)
                cancel.setOnClickListener {
                    viewModel.cancelDownload(getActivityInstance())
                    dialog.dismiss()
                }
                lifecycleScope.launch {
                    viewModel.downloadState.collect {
                        when (it) {
                            is MainViewModel.State.Downloading -> {
                                if (it.progress > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = it.progress
                                }
                            }
                            is MainViewModel.State.Done -> {
                                viewModel.openPackageInstaller(getActivityInstance(), it.fileUri)
                                viewModel.clearUpdate()
                                dialog.dismiss()
                            }
                            is MainViewModel.State.Failed -> {
                                Toast.makeText(
                                    getActivityInstance(),
                                    R.string.bs_update_download_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                dialog.dismiss()

                            }
                            else -> {}
                        }
                    }
                }
                update?.let { it ->
                    viewModel.startDownload(getActivityInstance(), it)
                } ?: run {
                    dialog.dismiss()
                }
            }.run {
                dialog = create()
                dialog.show()
            }
        }
        dialog = alertDialog.create()
        dialog.show()
    }

    private suspend fun getSearchAddress(address: String) = callbackFlow {
        withContext(Dispatchers.IO){
            trySend(SearchProgress.Progress)
            val matcher: Matcher = COORDS.matcher(address)

            if (matcher.matches()){
                val (la, ln) = matcher.group().split(",").map { it.trim().toDouble() }
                // a pasted value can be anything; MapLibre's LatLng throws outside these ranges
                if (abs(la) <= 90 && abs(ln) <= 180) trySend(SearchProgress.Complete(la, ln))
                else trySend(SearchProgress.Fail(getString(R.string.invalid_coords)))
            }else {
                try {
                    val found = Geocoder(getActivityInstance()).getFromLocationName(address, 1)?.firstOrNull()
                    if (found != null) {
                        trySend(SearchProgress.Complete(found.latitude, found.longitude))
                    } else {
                        trySend(SearchProgress.Fail(getString(R.string.address_not_found)))
                    }
                } catch (io : IOException){
                    trySend(SearchProgress.Fail(getString(R.string.no_internet)))
                }
            }
        }
        awaitClose { this.cancel() }
    }

    protected fun showStartNotification(address: String){
        val stopIntent = PendingIntent.getBroadcast(
            this, 0, Intent(this, StopSpoofReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notificationsChannel.showNotification(this){
            it.setSmallIcon(R.drawable.ic_stop)
            it.setContentTitle(getString(R.string.location_set))
            it.setContentText(address)
            it.setAutoCancel(true)
            it.setCategory(Notification.CATEGORY_EVENT)
            it.priority = NotificationCompat.PRIORITY_HIGH
            it.addAction(R.drawable.ic_stop, getString(R.string.stop), stopIntent)
        }
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }

    // metres between two corners of the visible map (the map's on-screen width), for MapScale
    protected fun metersBetween(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double =
        FloatArray(1).also { Location.distanceBetween(aLat, aLng, bLat, bLng, it) }[0].toDouble()

    // Get current location
    @SuppressLint("MissingPermission")
    protected fun getLastLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (checkPermissions()) {
            if (isLocationEnabled()) {
                fusedLocationClient.lastLocation.addOnCompleteListener(this) { task ->
                    val location: Location? = task.result
                    if (location == null) {
                        requestNewLocationData()
                    } else {
                        lat = location.latitude
                        lon = location.longitude
                        moveMapToNewLocation(true)
                    }
                }
            } else {
                showToast("Turn on location")
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
        } else {
            requestPermissions()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest.interval = 0
        mLocationRequest.fastestInterval = 0
        mLocationRequest.numUpdates = 1

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.requestLocationUpdates(
            mLocationRequest, mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation: Location = locationResult.lastLocation!!
            lat = mLastLocation.latitude
            lon = mLastLocation.longitude
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager: LocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        )
    }

    private fun checkPermissions(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return false
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_ID
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            getLastLocation()
        }
    }
}

// "lat, lng" as typed or pasted (e.g. "17.3616, 78.4747")
private val COORDS: Pattern = Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?")

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
