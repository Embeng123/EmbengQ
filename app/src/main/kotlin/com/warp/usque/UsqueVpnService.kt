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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import usqueandroid.Usqueandroid
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_VPN = 1001
        private const val LOCKED_PORT = 443
        private val PRESET_SNIS = listOf(
            "engage.cloudflareclient.com",
            "www.cloudflare.com",
            "www.visa.cn",
            "Custom"
        )
    }

    data class AppEntry(val label: String, val packageName: String)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("embengq_prefs", MODE_PRIVATE) }
    private val configFile by lazy { File(filesDir, "config.json") }

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
    private lateinit var fabConnect: FloatingActionButton
    private lateinit var appSearchInput: TextInputEditText
    private lateinit var appListContainer: LinearLayout
    private lateinit var appCountText: TextView
    private lateinit var langToggleBtn: MaterialButton

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
    private val primaryColor = Color.rgb(25, 118, 210)       // Material Blue #1976D2
    private val primaryDark = Color.rgb(21, 101, 192)        // #1565C0
    private val accentFab = Color.rgb(233, 30, 99)           // Pink / Accent #E91E63
    private val connectedGreen = Color.rgb(46, 125, 50)      // Green #2E7D32
    private val disconnectedRed = Color.rgb(198, 40, 40)     // Red #C62828
    private val bgColor = Color.rgb(245, 247, 250)           // Light gray background
    private val surfaceColor = Color.rgb(255, 255, 255)
    private val textMain = Color.rgb(33, 33, 33)
    private val textSub = Color.rgb(117, 117, 117)
    private val outlineColor = Color.rgb(224, 224, 224)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useEnglish = prefs.getBoolean("useEnglish", false)
        buildUi()
        loadSavedState()
        refreshState()
        resetSpeedMeter()
        handler.post(speedTicker)
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
        if (currentPageIndex == 1) ensureAppsLoaded()
        refreshState()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(bgColor)
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Header Top Bar ala v2rayNG
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(primaryColor)
            elevation = dp(4).toFloat()
            setPadding(dp(16), 0, dp(16), 0)
        }

        val appTitle = TextView(this).apply {
            text = "EmbengQ"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        langToggleBtn = MaterialButton(this).apply {
            text = if (useEnglish) "EN" else "ID"
            textSize = 12f
            setTextColor(primaryColor)
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            cornerRadius = dp(14)
            minHeight = dp(32)
            minimumWidth = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { toggleLanguage() }
        }

        topBar.addView(appTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(langToggleBtn, LinearLayout.LayoutParams(-2, dp(34)))
        mainLayout.addView(topBar, LinearLayout.LayoutParams(-1, dp(56)))

        // Tab Switcher (Koneksi / Per-Aplikasi)
        val tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(primaryColor)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val tabConfig = tabBtn(tr("Koneksi", "Connection"))
        val tabApps = tabBtn(tr("Per-Aplikasi", "Apps"))
        tabContainer.addView(tabConfig, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
        tabContainer.addView(tabApps, LinearLayout.LayoutParams(0, dp(38), 1f))
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

            val target = if (index == 0) configView else {
                ensureAppsLoaded()
                appsView
            }
            (target.parent as? ViewGroup)?.removeView(target)
            pageHost.addView(ScrollView(this).apply {
                isFillViewport = true
                addView(target)
            })
        }

        tabConfig.setOnClickListener { showPage(0) }
        tabApps.setOnClickListener { showPage(1) }
        mainLayout.addView(pageHost)

        // Status & Speed Bottom Bar (Khas v2rayNG)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surfaceColor)
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusText = TextView(this).apply {
            text = tr("Status: Terputus", "Status: Disconnected")
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(disconnectedRed)
        }
        speedText = TextView(this).apply {
            text = "↓ 0 B/s   ↑ 0 B/s"
            textSize = 12f
            setTextColor(textSub)
            setPadding(0, dp(2), 0, 0)
        }
        statusBox.addView(statusText)
        statusBox.addView(speedText)
        bottomBar.addView(statusBox, LinearLayout.LayoutParams(0, -2, 1f))
        mainLayout.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))

        root.addView(mainLayout)

        // Floating Action Button (FAB) Connect/Disconnect
        fabConnect = FloatingActionButton(this).apply {
            backgroundTintL
