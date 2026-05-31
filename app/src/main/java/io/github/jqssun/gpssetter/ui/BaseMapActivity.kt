package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
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
import androidx.core.content.ContextCompat
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
import io.github.jqssun.gpssetter.databinding.ActivityMapBinding
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.NotificationsChannel
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.StopGpsReceiver
import io.github.jqssun.gpssetter.utils.ext.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.properties.Delegates
import android.app.PendingIntent

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

    private val elevationOverlayProvider by lazy {
        ElevationOverlayProvider(this)
    }

    private val headerBackground by lazy {
        elevationOverlayProvider.compositeOverlayWithThemeSurfaceColorIfNeeded(
            resources.getDimension(R.dimen.bottom_sheet_elevation)
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Izin Notifikasi Diperlukan")
                .setMessage("Izin notifikasi dibutuhkan agar GPS aktif bisa ditampilkan. Aktifkan di Pengaturan > Aplikasi > GPS Setter > Notifikasi.")
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    })
                }
                .setNegativeButton("Nanti", null)
                .show()
        }
    }

    protected abstract fun getActivityInstance(): BaseMapActivity
    protected abstract fun hasMarker(): Boolean
    protected abstract fun initializeMap()
    protected abstract fun setupButtons()
    protected abstract fun moveMapToNewLocation(moveNewLocation: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycleScope.launchWhenCreated {
            setContentView(binding.root)
        }
        setSupportActionBar(binding.toolbar)
        initializeMap()
        applyWindowInsets()
        checkModuleEnabled()
        checkUpdates()
        setupNavView()
        setupButtons()
        setupDrawer()
        askNotificationPermission()
        if (PrefManager.isJoystickEnabled){
            startService(Intent(this, JoystickService::class.java))
        }
    }

    /**
     * App ini digambar edge-to-edge (konten masuk ke area status bar & navigation bar).
     * Tanpa penyesuaian, kontrol atas (toolbar + search) ketimpa status bar dan
     * kontrol bawah (kolom FAB, toggle, panel walk) ketimpa navigation bar / gesture bar.
     *
     * Di sini kita baca system bar insets lalu:
     * - dorong toolbar & search ke bawah status bar (padding/margin atas)
     * - dorong kontrol bawah ke atas navigation bar (margin bawah)
     */
    private fun applyWindowInsets() {
        val toolbarBaseTop = binding.toolbar.marginTop
        val searchBaseTop = binding.search.root.marginTop
        val barBasePaddingBottom = binding.bottomBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.container) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = toolbarBaseTop + bars.top
            }
            binding.search.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = searchBaseTop + bars.top
            }
            // Bottom bar membentang di belakang navigation bar: tambahkan inset
            // sebagai padding bawah supaya isi (tombol toggle) tetap di atas nav bar,
            // sementara warna latar bar mengisi area di belakangnya.
            binding.bottomBar.updatePadding(bottom = barBasePaddingBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.container)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> { }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Izin Notifikasi")
                        .setMessage("Aplikasi membutuhkan izin notifikasi untuk menampilkan status GPS yang sedang aktif.")
                        .setPositiveButton("Izinkan") { _, _ ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Tolak", null)
                        .show()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
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

        // Beri padding atas pada drawer agar header tidak ketimpa status bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(0, top, 0, 0)
            insets
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
                    setCancelable(true)
                    show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateXposedState()
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

    /**
     * Dialog Pengaturan ringkas yang dibuka dari tombol gear di peta.
     * Berisi: Floating Mode, Fused Mode, Random Location, selector Android OS,
     * dan parameter manual (Accuracy/Altitude/Bearing/Speed) yang hanya tampil
     * saat switch "Parameter Manual" diaktifkan.
     */
    protected fun openMapSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_map_settings, null)

        val switchFloating = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_floating_mode)
        val switchRandom = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_random_location)
        val switchAutoOff = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_auto_off_order)
        val osGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.os_toggle_group)
        val switchManual = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_manual_params)
        val manualContainer = view.findViewById<View>(R.id.manual_params_container)

        val sliderAccuracy = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_accuracy)
        val sliderAltitude = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_altitude)
        val sliderBearing = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_bearing)
        val sliderSpeed = view.findViewById<com.google.android.material.slider.Slider>(R.id.slider_speed)
        val valueAccuracy = view.findViewById<TextView>(R.id.value_accuracy)
        val valueAltitude = view.findViewById<TextView>(R.id.value_altitude)
        val valueBearing = view.findViewById<TextView>(R.id.value_bearing)
        val valueSpeed = view.findViewById<TextView>(R.id.value_speed)

        // --- Inisialisasi state dari PrefManager ---
        switchFloating.isChecked = PrefManager.isJoystickEnabled
        switchRandom.isChecked = PrefManager.isRandomPosition
        switchAutoOff.isChecked = PrefManager.isAutoOffOnOrder

        if (PrefManager.androidOsMode == PrefManager.OS_MODE_LEGACY) {
            osGroup.check(R.id.btn_os_legacy)
        } else {
            osGroup.check(R.id.btn_os_modern)
        }

        // Helper update label
        fun refreshLabels() {
            valueAccuracy.text = "${sliderAccuracy.value.toInt()} m"
            valueAltitude.text = "${sliderAltitude.value.toInt()} m"
            valueBearing.text = "${sliderBearing.value.toInt()}\u00B0"
            valueSpeed.text = "${sliderSpeed.value.toInt()} m/s"
        }

        // Nilai slider dari prefs (clamp ke rentang agar tidak crash)
        fun Float.clampTo(slider: com.google.android.material.slider.Slider) =
            coerceIn(slider.valueFrom, slider.valueTo)

        sliderAccuracy.value = (PrefManager.accuracy?.toFloatOrNull() ?: 10f).clampTo(sliderAccuracy)
        sliderAltitude.value = PrefManager.manualAltitude.clampTo(sliderAltitude)
        sliderBearing.value = PrefManager.manualBearing.clampTo(sliderBearing)
        sliderSpeed.value = PrefManager.manualSpeed.clampTo(sliderSpeed)
        refreshLabels()

        switchManual.isChecked = PrefManager.isManualParams
        manualContainer.visibility = if (PrefManager.isManualParams) View.VISIBLE else View.GONE

        // --- Listeners ---
        switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ensureOverlayPermission()) {
                    PrefManager.isJoystickEnabled = true
                    startService(Intent(this, JoystickService::class.java))
                    showToast("Floating Mode aktif")
                } else {
                    // izin belum ada, balikkan switch
                    switchFloating.isChecked = false
                }
            } else {
                PrefManager.isJoystickEnabled = false
                stopService(Intent(this, JoystickService::class.java))
                showToast("Floating Mode nonaktif")
            }
        }

        switchRandom.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isRandomPosition = isChecked
            showToast(if (isChecked) "Random Location aktif" else "Random Location nonaktif")
        }

        switchAutoOff.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isAutoOffOnOrder = isChecked
            if (isChecked) {
                if (!isNotificationListenerEnabled()) {
                    showToast("Aktifkan izin Notification Access untuk fitur ini")
                    requestNotificationListenerPermission()
                } else {
                    showToast("Auto-Off aktif: GPS mati otomatis saat dapat orderan")
                }
            } else {
                showToast("Auto-Off dinonaktifkan")
            }
        }

        osGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            PrefManager.androidOsMode = if (checkedId == R.id.btn_os_legacy) {
                PrefManager.OS_MODE_LEGACY
            } else {
                PrefManager.OS_MODE_MODERN
            }
        }

        switchManual.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isManualParams = isChecked
            manualContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                // Kembali ke otomatis: reset speed/bearing agar teleport diam
                applyManualParams(0f, 0f)
            } else {
                applyManualParams(sliderSpeed.value, sliderBearing.value)
            }
        }

        sliderAccuracy.addOnChangeListener { _, value, _ ->
            PrefManager.accuracy = value.toInt().toString()
            valueAccuracy.text = "${value.toInt()} m"
        }
        sliderAltitude.addOnChangeListener { _, value, _ ->
            PrefManager.manualAltitude = value
            valueAltitude.text = "${value.toInt()} m"
        }
        sliderBearing.addOnChangeListener { _, value, _ ->
            PrefManager.manualBearing = value
            valueBearing.text = "${value.toInt()}\u00B0"
            if (PrefManager.isManualParams) applyManualParams(sliderSpeed.value, value)
        }
        sliderSpeed.addOnChangeListener { _, value, _ ->
            PrefManager.manualSpeed = value
            valueSpeed.text = "${value.toInt()} m/s"
            if (PrefManager.isManualParams) applyManualParams(value, sliderBearing.value)
        }

        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.map_settings_title))
        alertDialog.setView(view)
        alertDialog.setPositiveButton(getString(R.string.settings_close), null)
        alertDialog.setNeutralButton(getString(R.string.open_full_settings)) { _, _ ->
            startActivity(Intent(this, ActivitySettings::class.java))
        }
        dialog = alertDialog.create()
        dialog.show()
    }

    /**
     * Terapkan speed/bearing manual ke lokasi yang sedang aktif (jika GPS jalan),
     * supaya perubahan slider langsung terasa tanpa harus stop/start.
     */
    private fun applyManualParams(speedMs: Float, bearingDeg: Float) {
        if (PrefManager.isStarted) {
            PrefManager.updateWithMotion(true, PrefManager.getLat, PrefManager.getLng, speedMs, bearingDeg)
        }
    }

    /** Pastikan izin overlay sudah ada (untuk Floating Mode/joystick). */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        showToast("Aktifkan izin tampilkan di atas aplikasi lain")
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
        )
        return false
    }

    /** Cek apakah izin Notification Access aktif (untuk Auto-Off Order). */
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    /** Buka layar pengaturan Notification Access. */
    private fun requestNotificationListenerPermission() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            showToast("Buka Settings > Apps > Notification Access secara manual")
        }
    }

    protected fun addFavoriteDialog() {
        addFavoriteDialogWithName("")
    }

    /**
     * Dialog tambah favorit dengan nama yang sudah terisi (bisa dari intent atau kosong).
     * User tetap bisa mengedit nama sebelum menyimpan.
     * Tampilkan tombol Skip/Nanti jika dipanggil dengan nama pre-filled (dari intent).
     */
    protected fun addFavoriteDialogWithName(prefilledName: String) {
        val fromIntent = prefilledName.isNotEmpty()
        alertDialog = MaterialAlertDialogBuilder(this).apply {
            val view = layoutInflater.inflate(R.layout.dialog, null)
            val editText = view.findViewById<EditText>(R.id.search_edittxt)
            editText.setText(prefilledName)
            // Pindahkan kursor ke akhir teks agar mudah diedit
            editText.setSelection(editText.text.length)

            if (fromIntent) {
                setTitle(getString(R.string.add_fav_dialog_title))
                setMessage("Simpan lokasi ini ke daftar favorit?")
                setNegativeButton("Lewati") { dialog, _ -> dialog.dismiss() }
            } else {
                setTitle(getString(R.string.add_fav_dialog_title))
            }

            setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val s = editText.text.toString()
                if (hasMarker()) {
                    showToast(getString(R.string.location_not_select))
                } else {
                    viewModel.storeFavorite(s, lat, lon)
                    viewModel.response.observe(getActivityInstance()) {
                        if (it == (-1).toLong()) showToast(getString(R.string.cant_save)) else showToast(getString(R.string.save))
                    }
                }
            }
            setView(view)
            show()
        }
    }

    protected fun openFavoriteListDialog() {
        getAllUpdatedFavList()
        alertDialog = MaterialAlertDialogBuilder(this)
        alertDialog.setTitle(getString(R.string.favorites))
        val view = layoutInflater.inflate(R.layout.fav,null)
        val rcv = view.findViewById<RecyclerView>(R.id.favorites_list)
        rcv.layoutManager = LinearLayoutManager(this)
        rcv.adapter = favListAdapter
        favListAdapter.onItemClick = {
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
                delay(3000)
                trySend(SearchProgress.Complete(matcher.group().split(",")[0].toDouble(),matcher.group().split(",")[1].toDouble()))
            }else {
                val geocoder = Geocoder(getActivityInstance())
                val addressList: List<Address>? = geocoder.getFromLocationName(address,3)

                try {
                    addressList?.let {
                        if (it.size == 1){
                           trySend(SearchProgress.Complete(addressList[0].latitude, addressList[0].longitude))
                        }else {
                            trySend(SearchProgress.Fail(getString(R.string.address_not_found)))
                        }
                    }
                } catch (io : IOException){
                    trySend(SearchProgress.Fail(getString(R.string.no_internet)))
                }
            }
        }
        awaitClose { this.cancel() }
    }

    protected fun showStartNotification(address: String) {
        val stopIntent = Intent(this, StopGpsReceiver::class.java).apply {
            action = StopGpsReceiver.ACTION_STOP_GPS
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, this::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentLat = PrefManager.getLat
        val currentLng = PrefManager.getLng

        lifecycleScope.launch(Dispatchers.IO) {
            val streetAddress = try {
                val geocoder = Geocoder(this@BaseMapActivity, Locale.getDefault())
                val results = geocoder.getFromLocation(currentLat, currentLng, 1)
                if (!results.isNullOrEmpty()) {
                    val addr = results[0]
                    val street = addr.thoroughfare ?: addr.subLocality ?: addr.locality
                    val number = addr.featureName?.takeIf { it.all { c -> c.isDigit() } } ?: ""
                    if (street != null) {
                        if (number.isNotEmpty()) "$street No.$number" else street
                    } else {
                        addr.getAddressLine(0) ?: "Lokasi tidak diketahui"
                    }
                } else {
                    "Lokasi tidak diketahui"
                }
            } catch (e: Exception) {
                "Lokasi tidak diketahui"
            }

            withContext(Dispatchers.Main) {
                notificationsChannel.showNotification(this@BaseMapActivity) {
                    it.setSmallIcon(R.drawable.ic_stop)
                    it.setContentTitle("📍 GPS Aktif")
                    it.setContentText(streetAddress)
                    it.setOngoing(true)
                    it.setAutoCancel(false)
                    it.setCategory(Notification.CATEGORY_SERVICE)
                    it.priority = NotificationCompat.PRIORITY_HIGH
                    it.setContentIntent(openAppPendingIntent)
                    it.addAction(
                        R.drawable.ic_stop,
                        "Stop",
                        stopPendingIntent
                    )
                }
            }
        }
    }

    protected fun cancelNotification(){
        notificationsChannel.cancelAllNotifications(this)
    }

    @SuppressLint("MissingPermission")
    protected fun getLastLocation() {
        if (PrefManager.isStarted) {
            lat = PrefManager.getLat
            lon = PrefManager.getLng
            moveMapToNewLocation(true)
            return
        }

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
        if (PrefManager.isStarted) {
            lat = PrefManager.getLat
            lon = PrefManager.getLng
            moveMapToNewLocation(true)
            return
        }

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

sealed class SearchProgress {
    object Progress : SearchProgress()
    data class Complete(val lat: Double , val lon : Double) : SearchProgress()
    data class Fail(val error: String?) : SearchProgress()
}
