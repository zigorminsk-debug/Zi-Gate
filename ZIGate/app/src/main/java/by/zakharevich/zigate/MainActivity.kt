package by.zakharevich.zigate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import by.zakharevich.zigate.data.BarrierStore
import by.zakharevich.zigate.data.Settings
import by.zakharevich.zigate.model.Barrier
import by.zakharevich.zigate.service.BarrierService
import by.zakharevich.zigate.service.ServiceStatus
import by.zakharevich.zigate.util.AdaptivePolling
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var barriersContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private var barrierItems: MutableList<View> = mutableListOf()

    private val needLocation = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val locGranted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                        hasLocationPermission()
            val callGranted = result[Manifest.permission.CALL_PHONE] == true ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED
            configureBasedOnPermissions(locGranted, callGranted)
            requestBatteryExemptionIfNeeded()
            requestOverlayPermission()
            requestBackgroundLocationIfNeeded()
        }

    // Result of the system "display over other apps" settings screen.
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Re-check and inform the user (no-op if already granted).
            if (hasOverlayPermission()) {
                Toast.makeText(this, "Разрешение «поверх других приложений» включено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Без разрешения «поверх других приложений» автозвонок из фона не сработает",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val statusListener: (ServiceStatus) -> Unit = { renderStatus(it) }

    /** Latest snapshot published by [BarrierService]; the single source for
     *  both the status line and the per-card distances (they must never be
     *  computed from different fixes). */
    private var latestStatus: ServiceStatus? = null

    /** In-memory copy of the barrier list, refreshed by [renderBarriers].
     *  Status updates arrive up to 1/s – they must NOT re-read/parse the
     *  JSON store every time (main-thread jank + battery). */
    private var barriersCache: MutableList<Barrier> = mutableListOf()

    /** Reused time formatter (creating one per status update is wasteful). */
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Set while programmatically updating the switch views so we don't
     *  re-fire the change listeners (which would otherwise start/stop the
     *  service on every screen refresh). */
    private var settingsRendering = false

    private var freshLocListener: LocationListener? = null
    private var freshLocHandler: Handler? = null
    private var askedBackground = false

    private companion object {
        /** Fresh-location capture ("Записать/Запросить координаты"): collect
         *  several GPS fixes and keep the most accurate one instead of
         *  trusting the very first (often cached) fix. */
        /** Minimum fixes to collect before an early stop is allowed. */
        const val MIN_FIXES = 4
        /** Fixes needed for the hard stop (GPS streams ~1/s => ~12 s). */
        const val MAX_FIXES = 12
        /** Accuracy (m) that is good enough for an early stop. */
        const val GOOD_ACCURACY_M = 8f
        /** Overall capture timeout (ms). */
        const val TIMEOUT_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Fail-open: individually guard each step so a single failure can
        // never crash the app on launch (the screen still shows with defaults).
        runCatching {
            barriersContainer = findViewById(R.id.barriers_container)
            tvEmpty = findViewById(R.id.tv_empty)
            findViewById<TextView>(R.id.tv_version).text =
                "ZI Gate v${BuildConfig.VERSION_NAME} · ${BuildConfig.DEVELOPER}"
        }
        runCatching { bindActions() }
        runCatching { renderBarriers() }
        runCatching { renderSettings() }
        runCatching { renderStatus(ServiceStatus.empty()) }
        runCatching { requestCriticalPermissions() }
    }

    override fun onStart() {
        super.onStart()
        BarrierService.addListener(statusListener)
        // Pull current status if the service is already running.
        startServiceRefresh()
    }

    override fun onStop() {
        super.onStop()
        BarrierService.removeListener(statusListener)
    }

    override fun onDestroy() {
        cancelFreshLocation()
        super.onDestroy()
    }

    private fun bindActions() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_barrier)
            .setOnClickListener { showAddBarrierDialog() }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_sync)
            .setOnClickListener { showSyncDialog() }

        findViewById<SwitchMaterial>(R.id.switch_auto)
            .setOnCheckedChangeListener { _, checked ->
                if (settingsRendering) return@setOnCheckedChangeListener
                Settings.setAutoEnabled(this, checked)
                if (checked) startService(BarrierService.ACTION_START)
                else stopServiceAndLocal()
                renderSettings()
            }

        findViewById<SwitchMaterial>(R.id.switch_wifi_gate)
            .setOnCheckedChangeListener { _, checked ->
                if (settingsRendering) return@setOnCheckedChangeListener
                Settings.setWifiGateEnabled(this, checked)
                startServiceRefresh()
                renderSettings()
            }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_select_wifi)
            .setOnClickListener { showWifiPicker() }

        findViewById<TextInputEditText>(R.id.et_pause_code).setOnFocusChangeListener { _, has ->
            if (!has) {
                Settings.setPauseCode(this, findViewById<TextInputEditText>(R.id.et_pause_code).text.toString())
                startServiceRefresh()
            }
        }
    }

    // ---------------- permissions ----------------
    private fun requestCriticalPermissions() {
        val toAsk = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) toAsk += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) toAsk += Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) toAsk += Manifest.permission.CALL_PHONE
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) toAsk += Manifest.permission.POST_NOTIFICATIONS

        if (toAsk.isNotEmpty()) {
            permLauncher.launch(toAsk.toTypedArray())
        } else {
            configureBasedOnPermissions(true, true)
            requestBatteryExemptionIfNeeded()
            requestOverlayPermission()
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (askedBackground) return
        if (Build.VERSION.SDK_INT < 29) return
        if (!hasLocationPermission()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        askedBackground = true
        runCatching {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }

    // ---------------- overlay (background activity start) ----------------
    fun hasOverlayPermission(): Boolean = runCatching {
        android.provider.Settings.canDrawOverlays(this)
    }.getOrDefault(false)

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !hasOverlayPermission()) {
            runCatching {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayLauncher.launch(intent)
            }
        }
    }

    private fun configureBasedOnPermissions(locOk: Boolean, callOk: Boolean) {
        if (!locOk) Toast.makeText(this, "Нет доступа к геолокации", Toast.LENGTH_LONG).show()
        if (!callOk) Toast.makeText(this, "Нет доступа к звонкам", Toast.LENGTH_LONG).show()
        // The switch can only be on if location is granted.
        val sw = findViewById<SwitchMaterial>(R.id.switch_auto)
        if (!locOk && sw.isChecked) {
            sw.isChecked = false
            Settings.setAutoEnabled(this, false)
            stopServiceAndLocal()
        }
    }

    private fun requestBatteryExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    val intent = Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }
    }

    // ---------------- service control ----------------
    private fun startService(action: String) {
        val intent = Intent(this, BarrierService::class.java).setAction(action)
        runCatching { ContextCompat.startForegroundService(this, intent) }
        by.zakharevich.zigate.util.KeepAlive.schedule(this)
    }

    private fun startServiceRefresh() {
        if (hasLocationPermission()) {
            runCatching { startService(BarrierService.ACTION_REFRESH) }
        }
    }

    private fun stopServiceAndLocal() {
        by.zakharevich.zigate.util.KeepAlive.cancel(this)
        stopService(Intent(this, BarrierService::class.java))
        BarrierService.emit(ServiceStatus.empty())
    }

    // ---------------- barriers list ----------------
    /** Position from the service status (stable/filtered); falls back to the
     *  raw last-known location only when the service has no fix yet. */
    private fun statusLocation(): Location? {
        val s = latestStatus
        if (s?.lat != null && s.lng != null) {
            return Location("zigate").apply {
                latitude = s.lat
                longitude = s.lng
            }
        }
        return null
    }

    /** Refresh only the distance labels on the visible cards from the latest
     *  service fix (called on every status update - no list rebuild, no I/O). */
    private fun updateBarrierDistances() {
        val s = latestStatus ?: return
        if (s.lat == null || s.lng == null) return
        val list = barriersCache
        if (list.size != barrierItems.size) return
        for (i in list.indices) {
            val b = list[i]
            val d = AdaptivePolling.distanceMeters(s.lat, s.lng, b.lat, b.lng)
            barrierItems[i].findViewById<TextView>(R.id.distance).text = "${d.toInt()} м"
        }
    }

    private fun renderBarriers() {
        barriersContainer.removeAllViews()
        barrierItems.clear()
        val list = BarrierStore.load(this)
        barriersCache = list
        val last = statusLocation() ?: lastKnownLocation()

        if (list.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            for (b in list) {
                val item = LayoutInflater.from(this).inflate(R.layout.item_barrier, barriersContainer, false)
                bindBarrierItem(item, b, last)
                barriersContainer.addView(item)
                barrierItems.add(item)
            }
        }
    }

    private fun bindBarrierItem(item: View, b: Barrier, last: Location?) {
        val iconView = item.findViewById<ImageView>(R.id.icon)
        iconView.setImageResource(iconRes(b.icon))
        // Tap on the barrier icon = immediate call to the barrier number
        // (same no-prompt policy as the automatic zone trigger).
        iconView.isClickable = true
        iconView.contentDescription = "Позвонить: ${b.name}"
        iconView.setOnClickListener { callBarrier(b) }
        item.findViewById<TextView>(R.id.name).text = b.name.trim()
        item.findViewById<TextView>(R.id.phone).text = "☎ ${b.phone}"
        item.findViewById<TextView>(R.id.radius).text = "Радиус зоны: ${b.radius.toInt()} м"
        item.findViewById<TextView>(R.id.repeat).text =
            getString(R.string.repeat_label, b.repeatIntervalSec.coerceIn(5, 180))
        val dist = if (last != null) AdaptivePolling.distanceMeters(
            last.latitude, last.longitude, b.lat, b.lng
        ) else null
        item.findViewById<TextView>(R.id.distance).text =
            if (dist == null) "—" else "${dist.toInt()} м"
        item.findViewById<ImageButton>(R.id.btn_edit).setOnClickListener {
            showBarrierDialog(existing = b)
        }
        // Tapping the card also opens the editor.
        item.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card)
            .setOnClickListener { showBarrierDialog(existing = b) }
        item.findViewById<ImageButton>(R.id.btn_delete).setOnClickListener {
            val list = BarrierStore.load(this).toMutableList()
            list.removeAll { it.id == b.id }
            BarrierStore.save(this, list)
            renderBarriers()
            startServiceRefresh()
        }
        // Record the current GPS point as this barrier's coordinates.
        item.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_record_coord)
            .setOnClickListener { recordCoordinates(b) }
    }

    /** Captures the phone's current position (a fresh GPS fix) and sets it as
     *  the barrier point. */
    /** Manual call: tapping the barrier icon on the card dials the barrier
     *  number immediately (ACTION_CALL; CALL_PHONE is granted at setup).
     *  Falls back to the dialer app if the direct call is blocked. */
    private fun callBarrier(b: Barrier) {
        val number = b.phone.trim().replace(" ", "").replace("-", "")
        if (number.isEmpty()) {
            Toast.makeText(this, "У шлагбаума не указан номер", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Нет разрешения на телефонные звонки", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(this, "Звонок: ${b.name}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            // Direct ACTION_CALL rejected (rare) - open the dialer prefilled.
            runCatching {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            }
        }
    }

    private fun recordCoordinates(b: Barrier) {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Разрешите доступ к геолокации", Toast.LENGTH_SHORT).show()
            return
        }
        requestFreshLocation(
            onRequestStart = {
                Toast.makeText(this, R.string.coord_requesting, Toast.LENGTH_SHORT).show()
            },
            onResult = { loc ->
                val list = BarrierStore.load(this).toMutableList()
                val idx = list.indexOfFirst { it.id == b.id }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(lat = loc.latitude, lng = loc.longitude)
                    BarrierStore.save(this, list)
                }
                renderBarriers()
                startServiceRefresh()
                val acc = if (loc.hasAccuracy()) loc.accuracy.toInt() else 0
                Toast.makeText(
                    this,
                    getString(R.string.toast_coord_recorded_acc, acc),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // ---------------- add / edit barrier ----------------
    private fun showAddBarrierDialog() = showBarrierDialog(existing = null)

    // Icon keys shown in the picker (mapped to drawables below).
    private val barrierIconKeys = arrayOf("gate1", "gate2", "gate3", "gate4", "gate5")

    private fun iconRes(key: String): Int = when (key) {
        "gate2" -> R.drawable.ic_gate_2
        "gate3" -> R.drawable.ic_gate_3
        "gate4" -> R.drawable.ic_gate_4
        "gate5" -> R.drawable.ic_gate_5
        else -> R.drawable.ic_gate_1
    }

    /** A small vertical block: a grey label above an input field. */
    private fun fieldBlock(label: String, field: EditText): View {
        val d = resources.displayMetrics.density
        val m = (16 * d).toInt()
        val tv = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, 0, 0, (4 * d).toInt())
        }
        field.apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(field)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(m, m, m, 0) }
        box.layoutParams = lp
        return box
    }

    /**
     * One dialog for both creating and editing a barrier, with explicit,
     * always-visible Save/Cancel buttons so they can never be hidden.
     *  - existing == null  → create a new barrier;
     *  - existing != null  → pre-fill the fields and update it on save.
     */
    private fun showBarrierDialog(existing: Barrier?) {
        val context = this
        val d = resources.displayMetrics.density
        val m = (16 * d).toInt()

        val iconKeys = barrierIconKeys
        val selectedIcon = arrayOf(existing?.icon ?: "gate1")
        if (iconKeys.none { it == selectedIcon[0] }) selectedIcon[0] = "gate1"

        // ---- fields ----
        val name = EditText(this).apply { hint = getString(R.string.hint_name) }
        val phone = EditText(this).apply {
            hint = getString(R.string.hint_phone)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val radius = EditText(this).apply {
            hint = getString(R.string.hint_radius)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val repeat = EditText(this).apply {
            hint = getString(R.string.hint_repeat)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        name.setText(existing?.name ?: "Шлагбаум")
        phone.setText(existing?.phone ?: "")
        radius.setText((existing?.radius?.toInt() ?: 40).toString())
        repeat.setText((existing?.repeatIntervalSec ?: 60).toString())

        var lat = existing?.lat ?: 0.0
        var lng = existing?.lng ?: 0.0

        // ---- icon picker (horizontally scrollable chips) ----
        val iconRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val iconContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(m, 0, m, 0)
        }
        val chips = mutableListOf<android.view.View>()
        for (key in iconKeys) {
            val chip = TextView(this).apply {
                text = "Шлагбаум"
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(13f)
                setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                setCompoundDrawablesWithIntrinsicBounds(iconRes(key), 0, 0, 0)
                compoundDrawablePadding = (10 * d).toInt()
                background = ContextCompat.getDrawable(context,
                    if (key == selectedIcon[0]) R.drawable.icon_chip_selected else R.drawable.icon_chip)
                setOnClickListener {
                    selectedIcon[0] = key
                    chips.forEachIndexed { i, c ->
                        val k = iconKeys[i]
                        c.background = ContextCompat.getDrawable(context,
                            if (k == selectedIcon[0]) R.drawable.icon_chip_selected else R.drawable.icon_chip)
                    }
                }
            }
            chips.add(chip)
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, (8 * d).toInt(), 0) }
            iconContainer.addView(chip)
        }
        iconRow.addView(iconContainer)

        // ---- location button: immediately starts GPS and polls a fresh fix ----
        val locBtn = TextView(context).apply {
            text = getString(R.string.btn_request_coord)
            setTextColor(ContextCompat.getColor(context, R.color.primary))
            setTextSize(14f)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_location, 0, 0, 0)
            compoundDrawablePadding = (8 * d).toInt()
        }
        if (lat != 0.0 && lng != 0.0) {
            locBtn.text = "Точка: %.6f, %.6f".format(lat, lng)
        }
        val updateLocLabel = { loc: Location ->
            lat = loc.latitude
            lng = loc.longitude
            locBtn.text = "Точка: %.6f, %.6f".format(loc.latitude, loc.longitude)
        }
        locBtn.setOnClickListener {
            requestFreshLocation(
                onRequestStart = { locBtn.text = getString(R.string.coord_requesting) },
                onResult = updateLocLabel
            )
        }

        // ---- scrollable content ----
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldBlock(getString(R.string.label_name), name))
            addView(fieldBlock(getString(R.string.label_phone), phone))
            addView(fieldBlock(getString(R.string.label_radius), radius))
            addView(fieldBlock(getString(R.string.label_repeat), repeat))
        }
        val iconLabel = TextView(context).apply {
            text = getString(R.string.label_icon)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(m, (14 * d).toInt(), m, (4 * d).toInt())
        }
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(content)
            addView(iconLabel)
            addView(iconRow)
            addView(locBtn)
        }
        scroll.addView(scrollContent)

        // ---- title ----
        val title = TextView(context).apply {
            text = if (existing == null) getString(R.string.dialog_add_barrier_title)
            else getString(R.string.dialog_edit_barrier_title)
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(m, (18 * d).toInt(), m, (10 * d).toInt())
        }

        // ---- explicit buttons (always visible) ----
        val cancelBtn = com.google.android.material.button.MaterialButton(context).apply {
            text = getString(R.string.btn_cancel)
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setBackgroundColor(0) // plain text button
        }
        val saveBtn = com.google.android.material.button.MaterialButton(context).apply {
            text = if (existing == null) getString(R.string.btn_save) else getString(R.string.btn_save_changes)
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.white_on_primary))
            setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
        }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(m, (6 * d).toInt(), m, (14 * d).toInt())
            addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
                .apply { setMargins((10 * d).toInt(), 0, 0, 0) })
        }

        val dialog = android.app.Dialog(context, R.style.Theme_ZIGate_Dialog)
        saveBtn.setOnClickListener {
            saveBarrier(existing, name, phone, radius, repeat, lat, lng, selectedIcon[0])
            dialog.dismiss()
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }

        // ---- assemble the dialog ----
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.dialog_bg)
            addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(btnRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val dm = context.resources.displayMetrics
        val lpAttrs = dialog.window?.attributes
        lpAttrs?.width = dm.widthPixels
        dialog.window?.attributes = lpAttrs
        dialog.show()
    }

    private fun saveBarrier(
        existing: Barrier?,
        name: EditText,
        phone: EditText,
        radius: EditText,
        repeat: EditText,
        lat: Double,
        lng: Double,
        icon: String
    ) {
        val r = (radius.text.toString().toFloatOrNull() ?: 40f).coerceIn(5f, 5000f)
        val rep = (repeat.text.toString().toIntOrNull() ?: 60).coerceIn(5, 180)
        val ic = if (barrierIconKeys.contains(icon)) icon else "gate1"
        val list = BarrierStore.load(this).toMutableList()
        if (existing == null) {
            val b = Barrier(
                id = java.util.UUID.randomUUID().toString(),
                name = name.text.toString().ifBlank { "Шлагбаум" },
                phone = phone.text.toString().trim(),
                lat = lat,
                lng = lng,
                radius = r,
                repeatIntervalSec = rep,
                icon = ic
            )
            list.add(b)
        } else {
            val b = Barrier(
                id = existing.id,
                name = name.text.toString().ifBlank { existing.name },
                phone = phone.text.toString().trim().ifBlank { existing.phone },
                lat = if (lat != 0.0 || lng != 0.0) lat else existing.lat,
                lng = if (lat != 0.0 || lng != 0.0) lng else existing.lng,
                radius = r,
                enabled = existing.enabled,
                lastTriggeredAt = existing.lastTriggeredAt,
                repeatIntervalSec = rep,
                icon = ic
            )
            val idx = list.indexOfFirst { it.id == existing.id }
            if (idx >= 0) list[idx] = b
            else list.add(b)
        }
        BarrierStore.save(this, list)
        renderBarriers()
        startServiceRefresh()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun getCurrentLocation(onResult: (Location) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Разрешите доступ к геолокации", Toast.LENGTH_SHORT).show()
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val last = lastKnownLocation()
        if (last != null) { onResult(last); return }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, ContextCompat.getMainExecutor(this)) { loc ->
                    if (loc != null) onResult(loc)
                    else Toast.makeText(this, "GPS недоступен", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
        // Fallback: last known network location.
        runCatching {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onResult(it) }
                ?: Toast.makeText(this, "GPS недоступен", Toast.LENGTH_SHORT).show()
        }
    }

    /** True fresh GPS poll: registers a listener and collects SEVERAL fixes,
     *  then returns the MOST ACCURATE one. The very first fix is often a stale
     *  cached position (it could be tens of meters off or even left from a
     *  previous session) - that was the reason a point recorded right at the
     *  barrier ended up 11+ m away and the card later showed 7 m at home.
     *  Strategy: wait for at least MIN_FIXES fixes; stop early only when the
     *  accuracy is already good (<= GOOD_ACCURACY_M); hard limit MAX_FIXES;
     *  overall timeout TIMEOUT_MS. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun requestFreshLocation(onRequestStart: () -> Unit, onResult: (Location) -> Unit) {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Разрешите доступ к геолокации", Toast.LENGTH_SHORT).show()
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                Toast.makeText(this, "Включите геолокацию (GPS)", Toast.LENGTH_SHORT).show()
                return
            }
        }
        onRequestStart()

        val handler = Handler(Looper.getMainLooper())
        var best: Location? = null
        var count = 0
        var finished = false
        var listener: LocationListener? = null

        fun accOf(l: Location?): Float =
            if (l == null || !l.hasAccuracy()) Float.MAX_VALUE else l.accuracy

        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            listener?.let { runCatching { lm.removeUpdates(it) } }
            val b = best
            if (b != null) onResult(b)
            else Toast.makeText(this, "Не удалось получить координаты", Toast.LENGTH_SHORT).show()
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                count++
                // Skip obviously stale/cache leftovers; keep the best fix.
                val fresh = kotlin.math.abs(System.currentTimeMillis() - location.time) < 10_000L
                if (fresh && accOf(location) < accOf(best)) best = location
                val done = (count >= MIN_FIXES && accOf(best) <= GOOD_ACCURACY_M) ||
                        count >= MAX_FIXES
                if (done) finish()
            }
        }
        cancelFreshLocation()
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener!!, Looper.getMainLooper())
            if (provider != LocationManager.NETWORK_PROVIDER &&
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                runCatching {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 0L, 0f, listener!!, Looper.getMainLooper()
                    )
                }
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Нет доступа к геолокации", Toast.LENGTH_SHORT).show()
            return
        }
        freshLocListener = listener
        freshLocHandler = handler
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }

    private fun cancelFreshLocation() {
        freshLocHandler?.removeCallbacksAndMessages(null)
        freshLocHandler = null
        val l = freshLocListener
        freshLocListener = null
        if (l != null) {
            runCatching {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(l)
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return runCatching {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
        }.getOrNull()
    }

    // ---------------- wifi picker ----------------
    /** Reads the system's saved (configured) WiFi networks. On Android 10+
     *  this is restricted (may return empty), so we rely on scan results too. */
    private fun savedWifiSsids(): List<String> {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return runCatching {
            wm.configuredNetworks?.mapNotNull { cfg ->
                (cfg as? WifiConfiguration)?.SSID?.trim('"')
            }?.filter { it.isNotEmpty() }
        }.getOrNull() ?: emptyList()
    }

    /** Scan results (visible networks). Needs location permission + location ON. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun scannedWifiSsids(): List<String> {
        if (!hasLocationPermission()) return emptyList()
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return runCatching {
            wm.scanResults?.map { it.SSID.trim() }?.filter { it.isNotEmpty() }
        }.getOrNull() ?: emptyList()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** All candidate SSIDs = configured + scanned, deduplicated & sorted. */
    private fun wifiCandidates(): List<String> =
        (savedWifiSsids() + scannedWifiSsids())
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    private fun showWifiPicker() {
        val context = this
        val selected = Settings.wifiPauseSet(this).toMutableSet()
        val manuallyAdded = sortedSetOf<String>()
        val density = resources.displayMetrics.density
        val m = (16 * density).toInt()
        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)

        // --- manual SSID entry row (always available, works on any Android) ---
        val manualRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(m, m, m, 0)
        }
        val manual = EditText(context).apply {
            hint = getString(R.string.hint_wifi_manual)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = com.google.android.material.button.MaterialButton(context).apply {
            text = getString(R.string.btn_add_wifi)
            isAllCaps = false
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
        manualRow.addView(manual)
        manualRow.addView(addBtn)

        // --- scrollable list of checkboxes ---
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, 0, m, 0)
        }
        val emptyHint = TextView(context).apply {
            text = getString(R.string.wifi_empty)
            textSize = 14f
            setTextColor(textSecondary)
            setPadding(m, m, m, m)
        }

        fun populate(candidates: List<String>) {
            listContainer.removeAllViews()
            // show selected (even if not currently visible) + candidates + manual
            val all = (candidates + selected + manuallyAdded)
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            emptyHint.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
            for (ssid in all) {
                val cb = CheckBox(context).apply {
                    text = ssid
                    isChecked = selected.contains(ssid)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                cb.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(ssid) else selected.remove(ssid)
                }
                listContainer.addView(cb)
            }
        }

        addBtn.setOnClickListener {
            val ssid = manual.text.toString().trim()
            if (ssid.isNotEmpty()) {
                manuallyAdded.add(ssid)
                selected.add(ssid)
                manual.setText("")
                populate(wifiCandidates())
            } else {
                Toast.makeText(context, R.string.hint_wifi_manual, Toast.LENGTH_SHORT).show()
            }
        }

        populate(wifiCandidates())

        // --- live scan refresh: show new networks as they appear ---
        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                populate(wifiCandidates())
            }
        }
        if (hasLocationPermission()) {
            runCatching {
                ContextCompat.registerReceiver(
                    this, scanReceiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_EXPORTED
                )
            }
            runCatching {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wm.startScan()
            }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(manualRow)
            addView(listContainer)
            addView(emptyHint)
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_ZIGate_Dialog)
            .setTitle(R.string.wifi_dialog_title)
            .setView(column)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_wifi_done, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Settings.setWifiPauseSet(this, selected)
                startServiceRefresh()
                renderSettings()
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { runCatching { unregisterReceiver(scanReceiver) } }
        dialog.show()
    }

    // ---------------- settings ----------------
    private fun renderSettings() {
        settingsRendering = true
        try {
            findViewById<SwitchMaterial>(R.id.switch_auto).isChecked = Settings.isAutoEnabled(this)
            findViewById<SwitchMaterial>(R.id.switch_wifi_gate).isChecked = Settings.isWifiGateEnabled(this)
            findViewById<TextInputEditText>(R.id.et_pause_code).setText(Settings.pauseCode(this))
        } finally {
            settingsRendering = false
        }

        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_select_wifi)
        val set = Settings.wifiPauseSet(this)
        btn.text = if (set.isEmpty()) getString(R.string.btn_select_wifi)
        else "${getString(R.string.btn_select_wifi)} · ${set.size}"

        // Show a warning when necessary for the auto-call to work from the
        // background (the phone app can't be launched from a foreground
        // service without the "display over other apps" permission).
        val overlay = findViewById<TextView>(R.id.tv_overlay_warning)
        if (Build.VERSION.SDK_INT >= 23 && Settings.isAutoEnabled(this) && !hasOverlayPermission()) {
            overlay.visibility = View.VISIBLE
        } else {
            overlay.visibility = View.GONE
        }
    }

    // ---------------- status ----------------
    private fun renderStatus(s: ServiceStatus) {
        latestStatus = s
        findViewById<TextView>(R.id.status_service).text =
            if (s.running) getString(R.string.status_on) else getString(R.string.status_off)
        findViewById<TextView>(R.id.status_gps).text = when {
            !s.running || !s.autoOn -> getString(R.string.status_off)
            s.pausedByWifi -> "${getString(R.string.status_paused)} · Wi-Fi"
            s.gpsWarmup -> getString(R.string.status_refining)
            !s.hasFix -> getString(R.string.status_waiting)
            s.inZone -> getString(R.string.status_in_zone)
            s.nearestDistance != null -> nearestLabel(s.nearestDistance!!)
            else -> getString(R.string.status_waiting)
        }
        findViewById<TextView>(R.id.status_wifi).text =
            if (s.pausedByWifi && s.wifiSsid != null) s.wifiSsid
            else if (s.running && !s.pausedByWifi) getString(R.string.status_none)
            else getString(R.string.status_none)
        findViewById<TextView>(R.id.status_lastfix).text =
            if (s.hasFix && s.lat != null && s.lng != null) {
                val d = s.nearestDistance
                val t = s.fixTimeMs ?: System.currentTimeMillis()
                val time = timeFmt.format(Date(t))
                val base = if (s.nearestName != null && d != null) "$time · ${s.nearestName}: ${d.toInt()} м"
                else if (d != null) "$time · ${d.toInt()} м"
                else "$time · —"
                if (s.accuracyM != null) "$base (±${s.accuracyM.toInt()} м)" else base
            } else if (s.gpsWarmup) getString(R.string.status_refining)
            else getString(R.string.status_none)
        findViewById<TextView>(R.id.status_poll).text =
            if (s.running && !s.pausedByWifi) "${s.pollPeriodMs / 1000f}s" else "-"
        // Keep the card distances in sync with the same service fix.
        updateBarrierDistances()
    }

    private fun nearestLabel(d: Float): String {
        val d0 = d.toInt()
        return when {
            d0 < 100 -> "$d0 м (близко)"
            d0 < 1000 -> "$d0 м"
            else -> "%.1f км".format(d / 1000f)
        }
    }

    // ---------------- sync (send / receive) ----------------
    private fun showSyncDialog() {
        val context = this
        val m = (16 * resources.displayMetrics.density).toInt()
        val urlField = EditText(context).apply {
            hint = "Сервер (http://...), необязательно"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(m, m, m, m)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, m, m, 0)
            addView(urlField)
        }

        val dlg = AlertDialog.Builder(context, R.style.Theme_ZIGate_Dialog)
            .setTitle(R.string.btn_sync)
            .setView(column)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton("Готово", null)
            .create()

        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, 0, m, 0)
        }

        fun btn(label: String, action: () -> Unit) = com.google.android.material.button.MaterialButton(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                action()
                dlg.dismiss()
            }
        }

        wrap.addView(btn("📤 Отправить на сервер (POST)") { sendToServer(urlField.text.toString()) })
        wrap.addView(btn("📥 Загрузить с сервера (GET)") { receiveFromServer(urlField.text.toString()) })
        wrap.addView(btn("📋 Копировать барьеры (JSON)") { copyBarriersJson() })
        wrap.addView(btn("📄 Вставить из буфера") { pasteBarriersJson() })

        column.addView(wrap)
        dlg.show()
    }

    private fun copyBarriersJson() {
        val json = BarrierStore.export(this)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zi_gate_barriers", json))
        Toast.makeText(this, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
    }

    private fun pasteBarriersJson() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show()
            return
        }
        runPeriodicImport(text)
    }

    private fun runPeriodicImport(text: String) {
        try {
            val n = BarrierStore.import(this, text)
            renderBarriers()
            startServiceRefresh()
            Toast.makeText(this, "Импортировано ($n)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendToServer(baseUrl: String) {
        val json = BarrierStore.export(this)
        if (baseUrl.isBlank()) { copyBarriersJson(); return }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { httpPost(baseUrl, json) }
            Toast.makeText(this@MainActivity,
                if (ok) "Отправлено" else "Ошибка отправки", Toast.LENGTH_SHORT).show()
        }
    }

    private fun receiveFromServer(baseUrl: String) {
        if (baseUrl.isBlank()) { pasteBarriersJson(); return }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { httpGet(baseUrl) }
            if (res != null) runPeriodicImport(res)
            else Toast.makeText(this@MainActivity, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
        }
    }

    private fun httpPost(url: String, body: String): Boolean {
        return try {
            val u = URL(url)
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) { false }
    }

    private fun httpGet(url: String): String? {
        return try {
            val u = URL(url)
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream.bufferedReader().readText() else null
            conn.disconnect()
            text
        } catch (e: Exception) { null }
    }
}
