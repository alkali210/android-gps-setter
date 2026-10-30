package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.FavListAdapter
import io.github.jqssun.gpssetter.route.RouteMath
import io.github.jqssun.gpssetter.route.RoutePlaybackService
import io.github.jqssun.gpssetter.route.GpxPlaybackState
import io.github.jqssun.gpssetter.route.RoutePoint
import io.github.jqssun.gpssetter.route.Routing
import io.github.jqssun.gpssetter.route.parseGpx
import kotlin.math.roundToInt
import io.github.jqssun.gpssetter.databinding.ActivityMapBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.StopSpoofReceiver
import io.github.jqssun.gpssetter.utils.applyAccent
import io.github.jqssun.gpssetter.utils.ext.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates

@AndroidEntryPoint
abstract class BaseMapActivity: AppCompatActivity() {

    protected var lat by Delegates.notNull<Double>()
    protected var lon by Delegates.notNull<Double>()
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
                binding.modeToggle.check(R.id.mode_static)
                viewModel.gpxOverlay = route.map { it.lat to it.lng }
                drawRoute(viewModel.gpxOverlay)
                RoutePlaybackService.start(this, route, kmh / 3.6f, loop = false, gpx = true)
                showToast(getString(R.string.route_started))
            }
            .show()
    }

    private val elevationOverlayProvider by lazy {
        ElevationOverlayProvider(this)
    }

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun setupButtons()
    // Always sync the selected marker; moveNewLocation controls camera movement only.
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)
    protected abstract fun centerMapOnLocation(latitude: Double, longitude: Double)

    // Route overlay (flavor-specific map): polyline + A/B markers, and a moving traveler dot.
    protected abstract fun drawRoute(points: List<Pair<Double, Double>>, fitCamera: Boolean = true)
    protected abstract fun clearRoute()
    protected abstract fun setTraveler(lat: Double, lng: Double)

    protected fun restoreGpxOverlay() {
        if (isGpxActive && viewModel.gpxOverlay.isNotEmpty()) {
            drawRoute(viewModel.gpxOverlay, fitCamera = false)
        }
    }

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
    protected val isGpxActive: Boolean get() = RoutePlaybackService.gpxState.value != null
    private var wasGpxPlaying = false

    private fun renderGpxPlayback(state: GpxPlaybackState?) {
        val playing = state != null
        if (playing) binding.modeToggle.check(R.id.mode_static)
        // Keep the panel's layout anchor so the floating buttons stay on screen.
        binding.routePanel.visibility = if (playing) View.INVISIBLE else View.VISIBLE
        binding.gpxControls.visibility = if (playing) View.VISIBLE else View.GONE
        binding.stopButton.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_stop)
        binding.stopButton.contentDescription = getString(if (playing) R.string.route_pause else R.string.location_unset)
        binding.startButton.contentDescription = getString(if (playing) R.string.route_resume else R.string.location_set)
        if (state != null) {
            binding.gpxSpeedValue.text = getString(R.string.route_speed_fmt, (state.speedMps * 3.6f).roundToInt())
            binding.gpxSpeedMinus.isEnabled = state.speedMps * 3.6f > 1f
        }
        binding.search.searchBox.isEnabled = !playing
        if (!playing && (wasGpxPlaying || viewModel.gpxOverlay.isNotEmpty())) {
            viewModel.gpxOverlay = emptyList()
            clearRoute()
            lat = PrefManager.getLat
            lon = PrefManager.getLng
            moveMapToNewLocation(false)
        }
        if (!routeMode) {
            val showStart = state?.paused ?: !viewModel.isStarted
            binding.startButton.visibility = if (showStart) View.VISIBLE else View.GONE
            binding.stopButton.visibility = if (showStart) View.GONE else View.VISIBLE
        }
        wasGpxPlaying = playing
    }

    protected fun handleGpxPlaybackClick(paused: Boolean): Boolean {
        if (!isGpxActive) return false
        RoutePlaybackService.setPaused(this, paused)
        return true
    }

    private fun changeGpxSpeed(deltaKmh: Int) {
        if (isGpxActive) RoutePlaybackService.adjustSpeed(this, deltaKmh)
    }

    private fun editGpxSpeed() {
        val state = RoutePlaybackService.gpxState.value ?: return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((state.speedMps * 3.6f).roundToInt().toString())
            selectAll()
        }
        val inset = (24 * resources.displayMetrics.density).roundToInt()
        val speedDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_speed_title)
            .setView(input, inset, 0, inset, 0)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        speedDialog.setOnShowListener {
            input.requestFocus()
            speedDialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            speedDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kmh = input.text.toString().trim().toIntOrNull()
                if (kmh == null || kmh < 1) {
                    input.error = getString(R.string.route_speed_invalid)
                } else {
                    if (isGpxActive) RoutePlaybackService.setSpeed(this, kmh)
                    speedDialog.dismiss()
                }
            }
        }
        speedDialog.show()
    }
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

    private fun setupRouteMode() {
        updateSpeedLabel()
        binding.modeToggle.check(R.id.mode_static)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            routeMode = checkedId == R.id.mode_route
            binding.routeControls.visibility = if (routeMode) View.VISIBLE else View.GONE
            if (routeMode) {
                binding.startButton.visibility = View.GONE
                binding.stopButton.visibility = View.GONE
                showSetup()
            } else {
                resetRoute()
                binding.startButton.visibility = if (viewModel.isStarted) View.GONE else View.VISIBLE
                binding.stopButton.visibility = if (viewModel.isStarted) View.VISIBLE else View.GONE
            }
        }
        binding.speedMinus.setOnClickListener { speedKmh = (speedKmh - 10).coerceAtLeast(5); updateSpeedLabel() }
        binding.speedPlus.setOnClickListener { speedKmh = (speedKmh + 10).coerceAtMost(300); updateSpeedLabel() }
        binding.gpxSpeedMinus.setOnClickListener { changeGpxSpeed(-5) }
        binding.gpxSpeedPlus.setOnClickListener { changeGpxSpeed(5) }
        binding.gpxSpeedValue.setOnClickListener { editGpxSpeed() }
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
        if (isGpxActive) return true
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
        viewModel.gpxOverlay = emptyList()
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
        setSupportActionBar(binding.toolbar)
        initializeMap()
        checkModuleEnabled()
        checkUpdates()
        setupNavView()
        setupButtons()
        setupDrawer()
        setupRouteMode()
        renderGpxPlayback(RoutePlaybackService.gpxState.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RoutePlaybackService.gpxState.collect { renderGpxPlayback(it) }
            }
        }
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

    private fun setupDrawer() {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val mDrawerToggle = object : ActionBarDrawerToggle(
            this,
            binding.container,
            binding.toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        ) {
            override fun onDrawerClosed(view: View) {
                super.onDrawerClosed(view)
                invalidateOptionsMenu()
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }
        binding.container.setDrawerListener(mDrawerToggle)
    }

    private fun setupNavView() {

        binding.mapContainer.map.setOnApplyWindowInsetsListener { _, insets ->
            val topInset: Int = insets.systemWindowInsetTop
            val bottomInset: Int = insets.systemWindowInsetBottom
            binding.navView.setPadding(0,topInset,0,0)
            insets.consumeSystemWindowInsets()
        }

        val progress = binding.search.searchProgress
        binding.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (isNetworkConnected()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val getInput = v.text.toString()
                        if (getInput.isNotEmpty()){
                            getSearchAddress(getInput).let {
                                it.collect { result ->
                                    when(result) {
                                        is SearchProgress.Progress -> {
                                            progress.visibility = View.VISIBLE
                                        }
                                        is SearchProgress.Complete -> {
                                            progress.visibility = View.GONE
                                            if (isGpxActive) return@collect
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

        binding.navView.setNavigationItemSelectedListener {
            when(it.itemId){
                R.id.get_favorite -> {
                    openFavoriteListDialog()
                }
                R.id.export_favorites -> {
                    exportFavoritesLauncher.launch("favorites.json")
                }
                R.id.import_favorites -> {
                    importFavoritesLauncher.launch(arrayOf("application/json"))
                }
                R.id.play_route -> {
                    if (checkPermissions()) gpxLauncher.launch(arrayOf("*/*")) else requestPermissions()
                }
                R.id.stop_route -> {
                    RoutePlaybackService.stop(this)
                }
                R.id.settings -> {
                    startActivity(Intent(this,ActivitySettings::class.java))
                }
                R.id.about -> {
                    aboutDialog()
                }
            }
            binding.container.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun checkModuleEnabled(){
        viewModel.isXposed.observe(this) { isXposed ->
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
        renderGpxPlayback(RoutePlaybackService.gpxState.value)
    }

    protected fun aboutDialog(){
        alertDialog = MaterialAlertDialogBuilder(this)
        layoutInflater.inflate(R.layout.about,null).apply {
            val  titlele = findViewById<TextView>(R.id.design_about_title)
            val  version = findViewById<TextView>(R.id.design_about_version)
            val  info = findViewById<TextView>(R.id.design_about_info)
            titlele.text = getString(R.string.app_name)
            version.text = BuildConfig.VERSION_NAME
            info.text = getString(R.string.about_info)
        }.run {
            alertDialog.setView(this)
            alertDialog.show()
        }
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

    private fun openFavoriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favorites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = favoriteClick@{
            if (isGpxActive) return@favoriteClick
            it.let {
                lat = it.lat!!
                lon = it.lng!!
            }
            moveMapToNewLocation(true)
            if (dialog.isShowing) dialog.dismiss()

        }
        favListAdapter.onItemDelete = {
            viewModel.deleteFavorite(it)
        }
        alertDialog.setView(view)
        dialog = alertDialog.create()
        dialog.show()

    }

    private fun getAllUpdatedFavList(){
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
                viewModel.doGetUserDetails()
                viewModel.allFavList.collect {
                    favListAdapter.submitList(it)
                }
            }
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
            val matcher: Matcher =
                Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?").matcher(address)

            if (matcher.matches()){
                trySend(SearchProgress.Complete(matcher.group().split(",")[0].toDouble(),matcher.group().split(",")[1].toDouble()))
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
                        centerMapOnLocation(location.latitude, location.longitude)
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
            val location = locationResult.lastLocation ?: return
            centerMapOnLocation(location.latitude, location.longitude)
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

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
