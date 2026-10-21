package com.warp.usque

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import usqueandroid.Usqueandroid
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_VPN = 1001
        private const val LOCKED_PORT = 443
        private val PRESET_SNIS = listOf(
            "classroom.google.com",
            "v.whatsapp.net",
            "live.iflix.com",
            "Custom"
        )
    }

    data class AppEntry(val label: String, val packageName: String)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("embengq_prefs", MODE_PRIVATE) }
    // config.json is the bundled/default account copied from assets on first launch.
    // config_update.json stores a newly registered account. Keep the default as fallback.
    private val configFile by lazy { File(filesDir, "config.json") }
    private val updatedConfigFile by lazy { File(filesDir, "config_update.json") }
    private val candidateConfigFile by lazy { File(filesDir, "config_update_candidate.json") }
    private val activeConfigFile: File
        get() = if (isConfigValid(updatedConfigFile)) updatedConfigFile else configFile

    // State
    private var useEnglish = false
    private var vpnRunning = false
    private var vpnGranted = false
    private var configDirty = false
    private var appsLoaded = false
    private var currentPageIndex = 0

    // UI Elements
    private lateinit var endpointInput: TextInputEditText
    private lateinit var sniSpinner: Spinner
    private lateinit var customSniWrap: View
    private lateinit var customSniInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var logText: TextView
    private lateinit var splitModeSwitch: MaterialSwitch
    private lateinit var connectButton: MaterialButton
    private lateinit var appSearchInput: TextInputEditText
    private lateinit var appListContainer: LinearLayout
    private lateinit var appCountText: TextView
    private lateinit var langToggleBtn: MaterialButton
    private lateinit var accountInfoText: TextView
    private lateinit var keyValueText: TextView
    private lateinit var toggleKeyBtn: MaterialButton
    private var keyVisible = false
    private var updatingKey = false

    // Traffic Stats
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSpeedTs = 0L
    private val speedTicker = object : Runnable {
        override fun run() {
            updateSpeedLine()
            handler.postDelayed(this, 1000L)
        }
    }

    // Apps & Selection
    private val allApps = mutableListOf<AppEntry>()
    private val selectedPackages = linkedSetOf<String>()

    // Palette Warna ala v2rayNG 2022
    private val primaryColor = Color.rgb(13, 17, 23)       // GitHub Dark background
    private val primaryDark = Color.rgb(240, 246, 252)     // GitHub Dark foreground
    private val accentFab = Color.rgb(240, 246, 252)
    private val connectedGreen = Color.rgb(63, 185, 80)
    private val disconnectedRed = Color.rgb(248, 81, 73)
    private val bgColor = Color.rgb(13, 17, 23)
    private val surfaceColor = Color.rgb(22, 27, 34)
    private val textMain = Color.rgb(240, 246, 252)
    private val textSub = Color.rgb(139, 148, 158)
    private val outlineColor = Color.rgb(48, 54, 61)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useEnglish = prefs.getBoolean("useEnglish", false)
        buildUi()
        loadSavedState()
        refreshAccountInfo()
        syncVpnStateFromService()
        refreshState()
        resetSpeedMeter()
        handler.post(speedTicker)
    }

    override fun onResume() {
        super.onResume()
        // Activity may be recreated while the foreground VPN service keeps running.
        syncVpnStateFromService()
        refreshState()
        updateSpeedLine()
    }

    private fun syncVpnStateFromService() {
        vpnRunning = prefs.getBoolean("vpn_connected", false)
    }

    override fun onDestroy() {
        handler.removeCallbacks(speedTicker)
        super.onDestroy()
    }

    private fun tr(id: String, en: String): String = if (useEnglish) en else id

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun log(msg: String) {
        if (::logText.isInitialized) logText.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun parseEndpointHost(value: String): String {
        val v = value.trim()
        if (v.isBlank()) return "162.159.198.2"
        if (v.startsWith("[") && v.contains("]")) return v.substringAfter("[").substringBefore("]")
        return if (v.count { it == ':' } == 1) v.substringBefore(':') else v
    }

    private fun normalizedEndpointHost(): String {
        val text = if (::endpointInput.isInitialized) endpointInput.text?.toString().orEmpty() else ""
        return parseEndpointHost(text.trim().ifBlank { "162.159.198.2" })
    }

    private fun toggleLanguage() {
        useEnglish = !useEnglish
        prefs.edit().putBoolean("useEnglish", useEnglish).apply()
        buildUi()
        loadSavedState()
        refreshAccountInfo()
        if (currentPageIndex == 1) ensureAppsLoaded()
        refreshState()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(bgColor) }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Header: brand first, language switch deliberately understated.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(primaryColor)
            setPadding(dp(18), dp(4), dp(16), dp(4))
        }
        val appTitle = TextView(this).apply {
            text = "EmbengQ"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textMain)
            letterSpacing = 0.01f
        }
        langToggleBtn = MaterialButton(this).apply {
            text = if (useEnglish) "EN" else "ID"
            textSize = 10f
            isAllCaps = false
            setTextColor(textSub)
            backgroundTintList = ColorStateList.valueOf(surfaceColor)
            cornerRadius = dp(10)
            minHeight = dp(30)
            minimumHeight = dp(30)
            minimumWidth = dp(42)
            setPadding(dp(4), 0, dp(4), 0)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { toggleLanguage() }
        }
        topBar.addView(appTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(langToggleBtn, LinearLayout.LayoutParams(-2, dp(30)))
        mainLayout.addView(topBar, LinearLayout.LayoutParams(-1, dp(60)))

        // Connection status is near the brand, not buried at the bottom.
        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(7), dp(20), dp(8))
            setBackgroundColor(primaryColor)
        }
        val statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusText = TextView(this).apply {
            text = tr("Status: Terputus", "Status: Disconnected")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(disconnectedRed)
        }
        speedText = TextView(this).apply {
            text = "↓ 0 B/s   ↑ 0 B/s"
            textSize = 13f
            setTextColor(disconnectedRed)
            setPadding(0, dp(2), 0, 0)
        }
        statusBox.addView(statusText)
        statusBox.addView(speedText)
        statusBar.addView(statusBox, LinearLayout.LayoutParams(0, -2, 1f))
        mainLayout.addView(statusBar, LinearLayout.LayoutParams(-1, -2))

        // Large, labeled connection control with a comfortable touch target.
        connectButton = MaterialButton(this).apply {
            text = tr("HUBUNGKAN VPN", "CONNECT VPN")
            textSize = 14f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            cornerRadius = dp(12)
            minHeight = dp(56)
            minimumHeight = dp(56)
            setTextColor(primaryColor)
            backgroundTintList = ColorStateList.valueOf(textMain)
            insetTop = 0
            insetBottom = 0
            elevation = dp(2).toFloat()
            contentDescription = tr("Hubungkan atau putuskan VPN", "Connect or disconnect VPN")
            setOnClickListener { if (vpnRunning) disconnectVpn() else connectVpn() }
        }
        val connectWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(primaryColor)
            addView(connectButton, LinearLayout.LayoutParams(-1, dp(56)))
        }
        mainLayout.addView(connectWrap, LinearLayout.LayoutParams(-1, -2))

        // Tabs
        val tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(primaryColor)
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        val tabConfig = tabBtn(tr("Koneksi", "Connection"))
        val tabApps = tabBtn(tr("Per-Aplikasi", "Apps"))
        tabContainer.addView(tabConfig, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        tabContainer.addView(tabApps, LinearLayout.LayoutParams(0, dp(40), 1f))
        mainLayout.addView(tabContainer)

        val pageHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(bgColor)
        }
        val configView = buildConfigLayout()
        val appsView = buildAppsLayout()
        fun showPage(index: Int) {
            currentPageIndex = index
            pageHost.removeAllViews()
            setTabSelected(tabConfig, index == 0)
            setTabSelected(tabApps, index == 1)
            val target = if (index == 0) configView else { ensureAppsLoaded(); appsView }
            (target.parent as? ViewGroup)?.removeView(target)
            pageHost.addView(ScrollView(this).apply {
                isFillViewport = true
                addView(target)
            })
        }
        tabConfig.setOnClickListener { showPage(0) }
        tabApps.setOnClickListener { showPage(1) }
        mainLayout.addView(pageHost)
        root.addView(mainLayout)
        setContentView(root)
        showPage(0)
    }

    private fun buildConfigLayout(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(80))

        // Account / key management card
        val accountCard = cardContainer()
        val accountBox = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val accountTitle = TextView(this@MainActivity).apply {
            text = tr("Akun WARP MASQUE", "WARP MASQUE Account")
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(textSub)
        }
        accountInfoText = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(textSub)
            setPadding(0, dp(6), 0, dp(8))
        }
        keyValueText = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(textMain)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = round(surfaceColor, dp(8), outlineColor)
            setTextIsSelectable(true)
        }
        val accountButtons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        toggleKeyBtn = MaterialButton(this@MainActivity).apply {
            text = tr("Tampilkan Key", "Show Key")
            isAllCaps = false
            textSize = 12f
            setOnClickListener {
                keyVisible = !keyVisible
                refreshAccountInfo()
            }
        }
        val updateKeyBtn = MaterialButton(this@MainActivity).apply {
            text = tr("Update Key", "Update Key")
            isAllCaps = false
            textSize = 12f
            setOnClickListener { updateKey() }
        }
        accountButtons.addView(toggleKeyBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        accountButtons.addView(updateKeyBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        accountBox.addView(accountTitle)
        accountBox.addView(accountInfoText)
        accountBox.addView(keyValueText)
        accountBox.addView(accountButtons)
        accountCard.addView(accountBox)
        addView(accountCard)
        addView(space(12))

        val card = cardContainer()
        val cardBox = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        // Dropdown Pilihan SNI
        cardBox.addView(label(tr("Pilihan SNI", "SNI Option")))
        sniSpinner = Spinner(this@MainActivity).apply {
            background = round(surfaceColor, dp(8), outlineColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val sniOptions = listOf(
                "classroom.google.com",
                "v.whatsapp.net",
                "live.iflix.com",
                tr("Kustom...", "Custom...")
            )
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sniOptions)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, id: Long) {
                    if (::customSniWrap.isInitialized) {
                        customSniWrap.visibility = if (pos == 3) View.VISIBLE else View.GONE
                    }
                    markDirty()
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        cardBox.addView(sniSpinner, LinearLayout.LayoutParams(-1, dp(48)))

        // Custom SNI Field
        customSniInput = TextInputEditText(this@MainActivity).apply {
            hint = "e.g. your-sni.com"
            setTextColor(textMain)
            textSize = 14f
            isSingleLine = true
            addTextChangedListener(dirtyWatcher())
        }
        customSniWrap = inputWrap(tr("SNI Kustom", "Custom SNI"), customSniInput).apply {
            visibility = View.GONE
        }
        cardBox.addView(customSniWrap)

        // Endpoint IP Input
        cardBox.addView(space(10))
        endpointInput = TextInputEditText(this@MainActivity).apply {
            hint = "162.159.198.2"
            setTextColor(textMain)
            textSize = 14f
            isSingleLine = true
            addTextChangedListener(dirtyWatcher())
        }
        cardBox.addView(inputWrap(tr("Endpoint IP", "Endpoint IP"), endpointInput))

        // Protocol information; the actual endpoint port remains locked to 443.
        cardBox.addView(space(6))
        val portLockedView = TextView(this@MainActivity).apply {
            text = "HTTP/3 over UDP/QUIC"
            textSize = 14f
            setTextColor(textSub)
            background = round(surfaceColor, dp(8), outlineColor)
            setPadding(dp(14), 0, dp(14), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        cardBox.addView(portLockedView, LinearLayout.LayoutParams(-1, dp(48)))

        // Reset default endpoint button
        val defaultBtn = MaterialButton(this@MainActivity).apply {
            text = tr("Gunakan Endpoint Bawaan", "Use Default Endpoint")
            textSize = 13f
            isAllCaps = false
            cornerRadius = dp(8)
            setTextColor(primaryColor)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(33, 38, 45))
            setOnClickListener {
                val def = Usqueandroid.getDefaultEndpoint(activeConfigFile.absolutePath)
                endpointInput.setText(parseEndpointHost(def))
                toast(tr("Endpoint bawaan dimuat", "Default endpoint loaded"))
            }
        }
        cardBox.addView(defaultBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })

        card.addView(cardBox)
        addView(card)

        // Mode Proxy Card
        addView(space(12))
        val modeCard = cardContainer()
        val modeBox = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        splitModeSwitch = MaterialSwitch(this@MainActivity).apply {
            text = tr("Bagi Terowongan (Split Tunneling)", "Split Tunneling Mode")
            textSize = 15f
            setTextColor(textMain)
            setOnCheckedChangeListener { _, isChecked ->
                markDirty()
                prefs.edit().putBoolean("splitMode", isChecked).apply()
                refreshAppList()
            }
        }
        val modeDesc = TextView(this@MainActivity).apply {
            text = tr("Jika aktif, hanya aplikasi yang dicentang di tab 'Per-Aplikasi' yang melewati VPN.", "If enabled, only selected apps in 'Apps' tab use VPN.")
            textSize = 12f
            setTextColor(textSub)
            setPadding(0, dp(4), 0, 0)
        }
        modeBox.addView(splitModeSwitch)
        modeBox.addView(modeDesc)
        modeCard.addView(modeBox)
        addView(modeCard)

        // Log / Info Ringkas
        addView(space(12))
        logText = TextView(this@MainActivity).apply {
            text = tr("Siap terhubung.", "Ready to connect.")
            textSize = 12f
            setTextColor(textSub)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        addView(logText)
    }

    private fun buildAppsLayout(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(80))

        val card = cardContainer()
        val appBox = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        appSearchInput = TextInputEditText(this@MainActivity).apply {
            hint = tr("Cari nama atau paket aplikasi...", "Search app name or package...")
            textSize = 14f
            setTextColor(textMain)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshAppList() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        appBox.addView(inputWrap(tr("Cari Aplikasi", "Search Apps"), appSearchInput))

        appCountText = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(textSub)
            setPadding(0, dp(4), 0, dp(8))
        }
        appBox.addView(appCountText)

        val btnRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val selectAllBtn = MaterialButton(this@MainActivity).apply {
            text = tr("Pilih Semua", "Select All")
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(8)
            setTextColor(primaryColor)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(33, 38, 45))
            setOnClickListener { selectAllVisibleApps() }
        }
        val clearAllBtn = MaterialButton(this@MainActivity).apply {
            text = tr("Hapus Pilihan", "Clear All")
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(8)
            setTextColor(textSub)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(33, 38, 45))
            setOnClickListener { clearAllVisibleApps() }
        }
        btnRow.addView(selectAllBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
        btnRow.addView(clearAllBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
        appBox.addView(btnRow)

        appBox.addView(MaterialDivider(this@MainActivity).apply {
            dividerColor = outlineColor
        }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(10); bottomMargin = dp(8) })

        appListContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        appBox.addView(appListContainer)

        card.addView(appBox)
        addView(card)
    }

    private fun tabBtn(label: String) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        cornerRadius = dp(19)
        minHeight = dp(36)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
    }

    private fun setTabSelected(btn: MaterialButton, selected: Boolean) {
        btn.setTextColor(if (selected) primaryDark else Color.WHITE)
        btn.backgroundTintList = ColorStateList.valueOf(if (selected) Color.WHITE else Color.argb(40, 255, 255, 255))
    }

    private fun cardContainer() = MaterialCardView(this).apply {
        radius = dp(12).toFloat()
        cardElevation = dp(1).toFloat()
        strokeWidth = 1
        strokeColor = outlineColor
        setCardBackgroundColor(surfaceColor)
        useCompatPadding = false
    }

    private fun label(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textSub)
        setPadding(dp(2), 0, dp(2), dp(4))
    }

    private fun inputWrap(labelStr: String, edit: TextInputEditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(labelStr))
        addView(TextInputLayout(this@MainActivity).apply {
            isHintEnabled = false
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = surfaceColor
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            setBoxStrokeColor(primaryColor)
            addView(edit)
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(1, stroke)
    }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun dirtyWatcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { markDirty() }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun markDirty() {
        configDirty = true
    }

    private fun currentSelectedSni(): String {
        return when (sniSpinner.selectedItemPosition) {
            0 -> PRESET_SNIS[0]
            1 -> PRESET_SNIS[1]
            2 -> PRESET_SNIS[2]
            else -> {
                val custom = customSniInput.text?.toString().orEmpty().trim()
                if (custom.isNotBlank()) custom else PRESET_SNIS[0]
            }
        }
    }

    private fun saveInputs() {
        prefs.edit()
            .putString("sni", currentSelectedSni())
            .putString("endpoint", normalizedEndpointHost())
            .putBoolean("splitMode", splitModeSwitch.isChecked)
            .putStringSet("selectedPackages", selectedPackages.toSet())
            .apply()
        configDirty = false
    }

    private fun loadSavedState() {
        val savedEndpoint = prefs.getString("endpoint", "162.159.198.2") ?: "162.159.198.2"
        endpointInput.setText(parseEndpointHost(savedEndpoint))

        val savedSni = prefs.getString("sni", PRESET_SNIS[0]) ?: PRESET_SNIS[0]
        val matchIndex = PRESET_SNIS.take(3).indexOf(savedSni)
        if (matchIndex >= 0) {
            sniSpinner.setSelection(matchIndex)
            customSniWrap.visibility = View.GONE
        } else {
            sniSpinner.setSelection(3)
            customSniWrap.visibility = View.VISIBLE
            customSniInput.setText(savedSni)
        }

        selectedPackages.clear()
        selectedPackages.addAll(prefs.getStringSet("selectedPackages", emptySet()) ?: emptySet())
        splitModeSwitch.isChecked = prefs.getBoolean("splitMode", false)
        configDirty = false
    }

    private fun ensureAppsLoaded() {
        if (appsLoaded) return
        appsLoaded = true
        appCountText.text = tr("Memuat aplikasi terinstal…", "Loading installed apps…")
        executor.execute {
            val pm = packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            val apps = pm.getInstalledPackages(flags)
                .asSequence()
                .mapNotNull { pkg -> pkg.applicationInfo }
                .filter { it.packageName != packageName }
                .map { info ->
                    val appLabel = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                    AppEntry(appLabel.ifBlank { info.packageName }, info.packageName)
                }
                .distinctBy { it.packageName }
                .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
                .toList()

            handler.post {
                allApps.clear()
                allApps.addAll(apps)
                refreshAppList()
            }
        }
    }

    private fun refreshAppList() {
        if (!::appListContainer.isInitialized) return
        if (!appsLoaded) return
        appListContainer.removeAllViews()
        val visible = visibleApps()
        visible.forEach { app ->
            appListContainer.addView(appRow(app), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        appCountText.text = if (splitModeSwitch.isChecked) {
            tr("Terpilih: ${selectedPackages.size} · Menampilkan: ${visible.size}/${allApps.size}", "Selected: ${selectedPackages.size} · Showing: ${visible.size}/${allApps.size}")
        } else {
            tr("Mode Global aktif (Pilihan disimpan) · ${visible.size}/${allApps.size}", "Global mode active (Selection saved) · ${visible.size}/${allApps.size}")
        }
    }

    private fun visibleApps(): List<AppEntry> {
        val q = appSearchInput.text?.toString().orEmpty().trim().lowercase()
        return allApps.asSequence()
            .filter { q.isBlank() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .sortedWith(compareByDescending<AppEntry> { selectedPackages.contains(it.packageName) }.thenBy { it.label.lowercase() }.thenBy { it.packageName })
            .toList()
    }

    private fun selectAllVisibleApps() {
        if (!appsLoaded) return
        selectedPackages.addAll(visibleApps().map { it.packageName })
        saveInputs()
        refreshAppList()
        toast(tr("Semua aplikasi terpilih", "All visible apps selected"))
    }

    private fun clearAllVisibleApps() {
        if (!appsLoaded) return
        selectedPackages.removeAll(visibleApps().map { it.packageName }.toSet())
        saveInputs()
        refreshAppList()
        toast(tr("Pilihan dibersihkan", "Cleared visible apps"))
    }

    private fun appRow(app: AppEntry): View = MaterialCheckBox(this).apply {
        text = "${app.label}\n${app.packageName}"
        setTextColor(textMain)
        textSize = 13f
        background = round(if (selectedPackages.contains(app.packageName)) Color.rgb(33, 38, 45) else surfaceColor, dp(8), outlineColor)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isChecked = selectedPackages.contains(app.packageName)
        setOnCheckedChangeListener { _, checked ->
            if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            saveInputs()
            refreshAppList()
        }
    }

    private fun isConfigValid(file: File): Boolean {
        return file.exists() && file.length() > 0L &&
            runCatching { Usqueandroid.isRegistered(file.absolutePath) }.getOrDefault(false)
    }

    private fun hasValidRegistration(): Boolean = isConfigValid(activeConfigFile)

    private fun refreshAccountInfo() {
        if (!::accountInfoText.isInitialized || !::keyValueText.isInitialized) return
        val file = activeConfigFile
        if (!file.exists() || file.length() == 0L) {
            accountInfoText.text = tr(
                "Konfigurasi akun belum ditemukan. Pastikan assets/config_default.json ikut di-build atau tekan Update Key.",
                "Account config not found. Ensure assets/config_default.json is packaged or tap Update Key."
            )
            keyValueText.text = tr("Konfigurasi belum tersedia", "Configuration unavailable")
            toggleKeyBtn.text = tr("Tampilkan Key", "Show Key")
            return
        }
        val registered = isConfigValid(file)
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (json == null) {
            accountInfoText.text = tr("File konfigurasi ada, tetapi JSON tidak valid.", "Config file exists, but its JSON is invalid.")
            keyValueText.text = tr("JSON tidak dapat dibaca", "Unable to read JSON")
            toggleKeyBtn.text = tr("Tampilkan Key", "Show Key")
            return
        }
        val id = json?.optString("id", "").orEmpty()
        val ipv4 = json?.optString("ipv4", "").orEmpty()
        val usingUpdate = file == updatedConfigFile
        accountInfoText.text = buildString {
            append(if (usingUpdate) tr("Akun aktif: hasil update", "Active account: updated") else tr("Akun aktif: bawaan", "Active account: default"))
            if (!registered) append("\n" + tr("Status konfigurasi: belum valid untuk core", "Config status: not validated by core"))
            if (id.isNotBlank()) append("\nID: $id")
            if (ipv4.isNotBlank()) append("\nIPv4: $ipv4")
        }
        keyValueText.text = if (json == null) {
            tr("JSON konfigurasi tidak dapat dibaca", "Configuration JSON could not be read")
        } else if (keyVisible) {
            // Display every top-level JSON field. The user explicitly chose to reveal secrets.
            json.keys().asSequence().toList().sorted().joinToString("\n\n") { key ->
                val value = json.opt(key)
                "$key:\n${if (value == null || value == JSONObject.NULL) "(null)" else value.toString()}"
            }
        } else {
            // Keep secret fields masked until the user explicitly taps Show Key.
            val secretNames = setOf("private_key", "access_token", "license", "token")
            json.keys().asSequence().toList().sorted().joinToString("\n") { key ->
                val value = json.opt(key)
                val shown = if (key.lowercase() in secretNames && value != null && value != JSONObject.NULL && value.toString().isNotBlank()) "••••••••••••" else value?.toString().orEmpty()
                "$key: $shown"
            }
        }
        toggleKeyBtn.text = if (keyVisible) tr("Sembunyikan Key", "Hide Key") else tr("Tampilkan Key", "Show Key")
    }

    private fun updateKey() {
        if (updatingKey) return
        if (vpnRunning) {
            toast(tr("Putuskan VPN sebelum update key", "Disconnect VPN before updating the key"))
            return
        }
        updatingKey = true
        log(tr("Mendaftarkan akun WARP baru…", "Registering a new WARP account…"))
        executor.execute {
            var error: String? = null
            try {
                runCatching { candidateConfigFile.delete() }
                val result = Usqueandroid.register(candidateConfigFile.absolutePath, "Android")
                val valid = result.isNullOrBlank() && isConfigValid(candidateConfigFile)
                if (!valid) {
                    error = result?.takeIf { it.isNotBlank() }
                        ?: tr("Registrasi gagal atau konfigurasi baru tidak valid", "Registration failed or new config is invalid")
                    candidateConfigFile.delete()
                } else {
                    if (updatedConfigFile.exists()) updatedConfigFile.delete()
                    if (!candidateConfigFile.renameTo(updatedConfigFile)) {
                        // rename can fail across filesystems; both files are in filesDir, but retain a safe fallback.
                        FileInputStream(candidateConfigFile).use { input ->
                            FileOutputStream(updatedConfigFile).use { output -> input.copyTo(output) }
                        }
                        candidateConfigFile.delete()
                    }
                    if (!isConfigValid(updatedConfigFile)) {
                        updatedConfigFile.delete()
                        error = tr("Konfigurasi baru gagal diverifikasi", "New config failed validation")
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                runCatching { candidateConfigFile.delete() }
            }
            handler.post {
                updatingKey = false
                refreshAccountInfo()
                if (error == null && isConfigValid(updatedConfigFile)) {
                    log(tr("Update key berhasil. Akun baru siap digunakan.", "Key updated. New account is ready."))
                    toast(tr("Update key berhasil", "Key update successful"))
                } else {
                    log(tr("Update key gagal: ", "Key update failed: ") + error.orEmpty())
                    toast(tr("Update gagal; akun sebelumnya tetap digunakan", "Update failed; previous account remains active"))
                }
            }
        }
    }

    private fun resetSpeedMeter() {
        lastRxBytes = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: 0L
        lastTxBytes = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: 0L
        lastSpeedTs = System.currentTimeMillis()
        if (::speedText.isInitialized) {
            speedText.text = "↓ 0 B/s   ↑ 0 B/s"
            speedText.setTextColor(if (vpnRunning) connectedGreen else disconnectedRed)
        }
    }

    private fun updateSpeedLine() {
        if (!::speedText.isInitialized) return
        if (!vpnRunning) {
            speedText.text = "↓ 0 B/s   ↑ 0 B/s"
            speedText.setTextColor(disconnectedRed)
            return
        }
        speedText.setTextColor(connectedGreen)
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: return
        val tx = TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: return
        if (lastSpeedTs <= 0L) { resetSpeedMeter(); return }
        val seconds = ((now - lastSpeedTs).coerceAtLeast(1)).toDouble() / 1000.0
        val down = ((rx - lastRxBytes).coerceAtLeast(0) / seconds).toLong()
        val up = ((tx - lastTxBytes).coerceAtLeast(0) / seconds).toLong()
        lastRxBytes = rx
        lastTxBytes = tx
        lastSpeedTs = now
        speedText.text = "↓ ${formatSpeed(down)}   ↑ ${formatSpeed(up)}"
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        return if (idx == 0) "${value.toLong()} ${units[idx]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[idx])
    }

    private fun refreshState(extra: String = "") {
        val state = if (vpnRunning) tr("Terhubung", "Connected") else tr("Terputus", "Disconnected")
        statusText.text = "${tr("Status", "Status")}: $state${if (extra.isNotBlank()) " · $extra" else ""}"
        statusText.setTextColor(if (vpnRunning) connectedGreen else disconnectedRed)

        if (::connectButton.isInitialized) {
            connectButton.text = if (vpnRunning) tr("PUTUSKAN VPN", "DISCONNECT VPN") else tr("HUBUNGKAN VPN", "CONNECT VPN")
            connectButton.backgroundTintList = ColorStateList.valueOf(if (vpnRunning) connectedGreen else textMain)
            connectButton.setTextColor(if (vpnRunning) Color.WHITE else primaryColor)
        }
    }

    private fun connectVpn() {
        saveInputs()
        if (vpnRunning) {
            toast(tr("VPN sudah berjalan", "Already running"))
            return
        }
        if (splitModeSwitch.isChecked && selectedPackages.isEmpty()) {
            toast(tr("Pilih minimal satu aplikasi dalam mode bagi terowongan", "Select at least one app for split tunneling"))
            return
        }
        if (!hasValidRegistration()) {
            refreshAccountInfo()
            log(tr("Konfigurasi akun bawaan tidak ditemukan atau tidak valid. Tambahkan assets/config_default.json atau tekan Update Key.", "Default account config is missing or invalid. Add assets/config_default.json or tap Update Key."))
            toast(tr("Konfigurasi akun belum tersedia", "Account configuration unavailable"))
            return
        }
        requestVpnAndStart()
    }

    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null && !vpnGranted) {
            startActivityForResult(prepareIntent, REQ_VPN)
            return
        }
        vpnGranted = true
        startTunnelNow()
    }

    private fun startTunnelNow() {
        val sni = currentSelectedSni()
        val endpoint = "${normalizedEndpointHost()}:$LOCKED_PORT"
        val splitMode = splitModeSwitch.isChecked
        val allowedApps = if (splitMode) ArrayList(selectedPackages) else arrayListOf<String>()

        log("")
        resetSpeedMeter()
        vpnRunning = false
        prefs.edit().putBoolean("vpn_connected", false).putString("vpn_state", "connecting").apply()
        refreshState(tr("Menghubungkan…", "Connecting…"))

        val intent = Intent(this, UsqueVpnService::class.java)
            .putExtra("configPath", activeConfigFile.absolutePath)
            .putExtra("sni", sni)
            .putExtra("endpoint", endpoint)
            .putExtra("splitMode", splitMode)
            .putStringArrayListExtra("allowedApps", allowedApps)
        startService(intent)
        // The service callback will mark the UI connected only after the native tunnel connects.
    }

    private fun disconnectVpn() {
        log(tr("Menghentikan layanan VPN…", "Stopping VPN service…"))
        vpnRunning = false
        prefs.edit().putBoolean("vpn_connected", false).putString("vpn_state", "stopping").apply()
        refreshState(tr("Menghentikan…", "Stopping…"))
        resetSpeedMeter()

        UsqueVpnService.stopActiveTunnel()
        runCatching { startService(Intent(this, UsqueVpnService::class.java).setAction(UsqueVpnService.ACTION_STOP)) }
        handler.postDelayed(Runnable {
            runCatching { stopService(Intent(this, UsqueVpnService::class.java)) }
            vpnRunning = false
            prefs.edit().putBoolean("vpn_connected", false).putString("vpn_state", "disconnected").apply()
            refreshState(tr("Terputus", "Disconnected"))
            log(tr("VPN telah dinonaktifkan.", "VPN stopped."))
        }, 500L)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            vpnGranted = true
            if (hasValidRegistration()) startTunnelNow() else connectVpn()
        } else if (requestCode == REQ_VPN) {
            toast(tr("Izin VPN ditolak", "VPN permission denied"))
        }
    }
}
