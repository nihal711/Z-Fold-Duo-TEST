package com.foldduo.hinge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.foldduo.hinge.link.AngleSource
import com.foldduo.hinge.link.DebugReport
import com.foldduo.hinge.link.LinkStatus
import com.foldduo.hinge.link.PairingNotifier
import com.foldduo.hinge.overlay.HingeOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var sourceText: TextView
    private lateinit var angleText: TextView
    private lateinit var rateText: TextView
    private lateinit var modeText: TextView
    private lateinit var statusText: TextView
    private lateinit var prerequisiteText: TextView
    private lateinit var streamAngleText: TextView
    private lateinit var streamCaptureText: TextView
    private lateinit var coarseSensorText: View
    private lateinit var wallpaperHintText: TextView
    private lateinit var wallpaperButton: Button
    private lateinit var debugReportButton: Button
    private lateinit var setupPanel: View
    private lateinit var localNetworkHint: View
    private lateinit var codeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var overlayHelp: View
    private lateinit var demoButton: Button
    private lateinit var deviceText: TextView
    private lateinit var sensorText: TextView
    private lateinit var deviceStatesText: TextView
    private var previousTimestamp = 0L
    private var updateHz = Float.NaN
    private var lastSource: AngleSource? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        sourceText = findViewById(R.id.sourceText)
        angleText = findViewById(R.id.angleText)
        rateText = findViewById(R.id.rateText)
        modeText = findViewById(R.id.modeText)
        statusText = findViewById(R.id.statusText)
        prerequisiteText = findViewById(R.id.prerequisiteText)
        streamAngleText = findViewById(R.id.streamAngleText)
        streamCaptureText = findViewById(R.id.streamCaptureText)
        coarseSensorText = findViewById(R.id.coarseSensorText)
        wallpaperHintText = findViewById(R.id.wallpaperHintText)
        wallpaperButton = findViewById(R.id.wallpaperButton)
        wallpaperButton.setOnClickListener { open(Intent(Intent.ACTION_SET_WALLPAPER)) }
        debugReportButton = findViewById(R.id.debugReportButton)
        setupPanel = findViewById(R.id.setupPanel)
        localNetworkHint = findViewById(R.id.localNetworkHint)
        codeInput = findViewById(R.id.codeInput)
        pairButton = findViewById(R.id.pairButton)
        overlayStatus = findViewById(R.id.overlayStatus)
        overlayHelp = findViewById(R.id.overlayHelp)
        demoButton = findViewById(R.id.demoButton)
        deviceText = findViewById(R.id.deviceText)
        sensorText = findViewById(R.id.sensorText)
        deviceStatesText = findViewById(R.id.deviceStatesText)

        findViewById<Button>(R.id.wirelessDebuggingButton).setOnClickListener { open(SettingsLinks.wirelessDebugging()) }
        findViewById<Button>(R.id.developerOptionsButton).setOnClickListener { open(SettingsLinks.developerOptions()) }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            open(SettingsLinks.accessibilityService(ComponentName(this, HingeOverlayService::class.java)))
        }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener { open(SettingsLinks.appInfo(packageName)) }
        findViewById<Button>(R.id.copyDiagnosticsButton).setOnClickListener { copyDiagnostics() }
        debugReportButton.setOnClickListener { collectDebugReport() }
        demoButton.setOnClickListener { HingeOverlayService.instance?.playDemo() }
        pairButton.setOnClickListener { pair() }

        deviceText.text = getString(
            R.string.device_line,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            Build.DISPLAY,
        )
        sensorText.text = getString(R.string.sensor_line, AngleRuntime.publicSensorDescription)
        localNetworkHint.visibility = if (Build.VERSION.SDK_INT >= 37) View.VISIBLE else View.GONE

        scope.launch { AngleRuntime.status.collectLatest(::showStatus) }
        scope.launch { AngleRuntime.sample.collectLatest { it?.let(::render) } }
        scope.launch { AngleRuntime.pairEndpoint.collectLatest { showStatus(AngleRuntime.status.value) } }
        scope.launch { AngleRuntime.deviceStates.collectLatest { catalog ->
            deviceStatesText.text = if (catalog.isEmpty) {
                getString(R.string.device_states_unknown)
            } else {
                getString(R.string.device_states_line, catalog.toString())
            }
        } }
        requestRuntimePermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayState()
        showStatus(AngleRuntime.status.value)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshOverlayState() {
        val enabled = HingeOverlayService.isEnabled(this)
        overlayStatus.text = getString(if (enabled) R.string.overlay_on else R.string.overlay_off)
        overlayHelp.visibility = if (enabled) View.GONE else View.VISIBLE
        demoButton.isEnabled = enabled && HingeOverlayService.instance != null
    }

    private fun pair() {
        val code = codeInput.text.toString().filter(Char::isDigit)
        pairButton.isEnabled = false
        scope.launch(Dispatchers.IO) {
            val result = AngleRuntime.pair(code)
            runOnUiThread {
                pairButton.isEnabled = true
                if (result.isSuccess) {
                    codeInput.text.clear()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(codeInput.windowToken, 0)
                }
            }
        }
    }

    private fun showStatus(status: LinkStatus) {
        statusText.text = describe(status)
        val connected = status is LinkStatus.Connected
        setupPanel.visibility = if (connected) View.GONE else View.VISIBLE
        prerequisiteText.text = prerequisiteHint(status)
        prerequisiteText.visibility = if (prerequisiteText.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val angleLive = (status as? LinkStatus.Connected)?.angleLive == true
        val captureLive = (status as? LinkStatus.Connected)?.captureLive == true
        val connectedStatus = status as? LinkStatus.Connected
        streamAngleText.text = withDetail(
            getString(if (angleLive) R.string.stream_angle_live else R.string.stream_angle_down),
            connectedStatus?.angleDetail,
        )
        streamCaptureText.text = withDetail(
            getString(if (captureLive) R.string.stream_capture_live else R.string.stream_capture_down),
            connectedStatus?.captureDetail?.takeUnless { captureLive },
        )
        modeText.text = getString(if (connected) R.string.mode_full else R.string.mode_basic)
        val wallpaperMissing = connectedStatus?.foldWallpaperMissing == true && !angleLive
        wallpaperHintText.visibility = if (wallpaperMissing) View.VISIBLE else View.GONE
        wallpaperButton.visibility = wallpaperHintText.visibility
        if (wallpaperMissing) {
            wallpaperHintText.text = getString(
                R.string.fold_wallpaper_missing,
                connectedStatus?.wallpaperComponent?.substringAfterLast('.') ?: "unknown",
            )
        }
        coarseSensorText.visibility = if (!angleLive && !wallpaperMissing && AngleRuntime.publicSensorTooCoarse) View.VISIBLE else View.GONE
        sensorText.text = getString(R.string.sensor_line, AngleRuntime.publicSensorDescription)
        if (AngleRuntime.sample.value == null) {
            sourceText.text = getString(R.string.angle_source_none)
        }
    }

    private fun withDetail(base: String, detail: String?): CharSequence =
        if (detail.isNullOrBlank()) base else getString(R.string.stream_detail, base, detail)

    private fun describe(status: LinkStatus): CharSequence = when (status) {
        LinkStatus.WaitingForWirelessDebugging -> getString(R.string.status_waiting)
        is LinkStatus.Connecting -> getString(R.string.status_connecting, status.host, status.port)
        LinkStatus.PairingRequired -> getString(R.string.status_pairing_required)
        LinkStatus.Pairing -> getString(R.string.status_pairing)
        LinkStatus.Paired -> getString(R.string.status_paired)
        is LinkStatus.PairingFailed -> getString(R.string.status_pairing_failed, status.reason)
        is LinkStatus.Connected -> getString(R.string.status_connected, status.host, status.port)
        is LinkStatus.Retrying -> getString(
            R.string.status_retrying,
            status.reason,
            (status.nextAttemptInMs / 1000).coerceAtLeast(1),
        )
        is LinkStatus.Disconnected -> getString(R.string.status_disconnected, status.reason)
    }

    /** A one-line nudge about whichever prerequisite is missing, checked from public settings. */
    private fun prerequisiteHint(status: LinkStatus): CharSequence? {
        if (status is LinkStatus.Connected || status is LinkStatus.Pairing) return null
        AngleRuntime.pairEndpoint.value?.let { return getString(R.string.status_pairing_endpoint, it.port) }
        val resolver = contentResolver
        if (Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0) {
            return getString(R.string.status_developer_options_off)
        }
        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 0) {
            return getString(R.string.status_adb_off)
        }
        if (Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 0) {
            return getString(R.string.status_wireless_debugging_off)
        }
        return null
    }

    private fun render(sample: AngleSample) {
        if (sample.source != lastSource) {
            lastSource = sample.source
            previousTimestamp = 0L
            updateHz = Float.NaN
            sourceText.text = getString(
                when (sample.source) {
                    AngleSource.PRIVATE_ADB -> R.string.angle_source_private
                    AngleSource.PUBLIC_SENSOR -> R.string.angle_source_public
                },
            )
        }
        if (previousTimestamp > 0 && sample.timestampNanos > previousTimestamp) {
            val instant = 1_000_000_000f / (sample.timestampNanos - previousTimestamp)
            updateHz = if (updateHz.isFinite()) updateHz * 0.85f + instant * 0.15f else instant
        }
        previousTimestamp = sample.timestampNanos
        angleText.text = String.format(Locale.getDefault(), getString(R.string.angle_format), sample.angle)
        rateText.text = if (updateHz.isFinite()) {
            String.format(Locale.getDefault(), getString(R.string.rate_format), updateHz)
        } else {
            getString(R.string.rate_placeholder)
        }
    }

    private fun collectDebugReport() {
        debugReportButton.isEnabled = false
        Toast.makeText(this, R.string.debug_report_collecting, Toast.LENGTH_SHORT).show()
        scope.launch {
            val report = try {
                DebugReport.collect(this@MainActivity)
            } catch (error: Exception) {
                "debug report failed: ${error.javaClass.simpleName}: ${error.message}"
            }
            debugReportButton.isEnabled = true
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("ZFoldDuo debug report", report))
            Toast.makeText(this@MainActivity, R.string.debug_report_ready, Toast.LENGTH_LONG).show()
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.debug_report_share_title))
                putExtra(Intent.EXTRA_TEXT, report)
            }
            runCatching { startActivity(Intent.createChooser(share, getString(R.string.debug_report_share_title))) }
        }
    }

    private fun copyDiagnostics() {
        val report = buildString {
            appendLine(deviceText.text)
            appendLine("ZFoldDuo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Link: ${describe(AngleRuntime.status.value)}")
            appendLine(streamAngleText.text)
            appendLine(streamCaptureText.text)
            appendLine(modeText.text)
            appendLine(sensorText.text)
            appendLine("Sensors:")
            appendLine(AngleRuntime.describeSensors())
            appendLine(deviceStatesText.text)
            appendLine(overlayStatus.text)
            appendLine("Angle: ${angleText.text} (${sourceText.text})")
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("ZFoldDuo diagnostics", report))
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Deep links into Settings vary by OEM; fall back to the generic page.
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.ACCESS_LOCAL_NETWORK
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            // A pairing dialog may already be open; re-post the prompt now that we may notify.
            AngleRuntime.pairEndpoint.value?.let { PairingNotifier.showCodePrompt(this, it.port) }
        }
    }

    private companion object {
        const val PERMISSION_REQUEST = 37
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}

/** Deep links into Settings. The fragment-args extras are what Settings itself uses to highlight a row. */
object SettingsLinks {
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    fun developerOptions(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    fun wirelessDebugging(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
        putExtra(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless")
        putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, "toggle_adb_wireless") })
    }

    fun accessibilityService(component: ComponentName): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        val flattened = component.flattenToString()
        putExtra(EXTRA_FRAGMENT_ARG_KEY, flattened)
        putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, flattened) })
    }

    fun appInfo(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
}
