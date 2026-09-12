package com.foldduo.hinge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import com.foldduo.hinge.overlay.HingeOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var statusText: TextView
    private lateinit var angleText: TextView
    private lateinit var detailText: TextView
    private lateinit var rateText: TextView
    private lateinit var setupPanel: View
    private lateinit var endpointText: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var demoButton: Button
    private var previousTimestamp = 0L
    private var updateHz = Float.NaN

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        statusText = findViewById(R.id.statusText)
        angleText = findViewById(R.id.angleText)
        detailText = findViewById(R.id.detailText)
        rateText = findViewById(R.id.rateText)
        setupPanel = findViewById(R.id.setupPanel)
        endpointText = findViewById(R.id.endpointText)
        codeInput = findViewById(R.id.codeInput)
        pairButton = findViewById(R.id.pairButton)
        overlayStatus = findViewById(R.id.overlayStatus)
        demoButton = findViewById(R.id.demoButton)

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        demoButton.setOnClickListener { HingeOverlayService.instance?.playDemo() }
        pairButton.setOnClickListener { pair() }

        scope.launch { AngleRuntime.status.collectLatest(::showStatus) }
        scope.launch { AngleRuntime.sample.collectLatest { it?.let(::render) } }
        scope.launch { AngleRuntime.pairEndpoint.collectLatest { runOnUiThread(::renderDiscovery) } }
        scope.launch { AngleRuntime.connectEndpoint.collectLatest { runOnUiThread(::renderDiscovery) } }
        requestLocalNetworkPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val enabled = HingeOverlayService.isEnabled(this)
        overlayStatus.text = if (enabled) "画面オーバーレイ ON" else "画面オーバーレイ OFF"
        demoButton.isEnabled = enabled && HingeOverlayService.instance != null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pair() {
        val code = codeInput.text.toString().filter(Char::isDigit)
        pairButton.isEnabled = false
        scope.launch {
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

    private fun renderDiscovery() {
        val pair = AngleRuntime.pairEndpoint.value
        val connect = AngleRuntime.connectEndpoint.value
        endpointText.text = when {
            pair != null -> "ペアリング先を検出  port ${pair.port}"
            connect != null -> "ワイヤレスデバッグ検出済み"
            else -> "ワイヤレスデバッグを待っています"
        }
    }

    private fun showStatus(value: String) = runOnUiThread {
        statusText.text = value
        setupPanel.visibility = if (AngleRuntime.sample.value != null) View.GONE else View.VISIBLE
    }

    private fun render(sample: AngleSample) = runOnUiThread {
        if (previousTimestamp > 0 && sample.timestampNanos > previousTimestamp) {
            val instant = 1_000_000_000f / (sample.timestampNanos - previousTimestamp)
            updateHz = if (updateHz.isFinite()) updateHz * 0.85f + instant * 0.15f else instant
        }
        previousTimestamp = sample.timestampNanos
        statusText.text = "Samsung内部ヒンジ計測中"
        setupPanel.visibility = View.GONE
        angleText.text = String.format(Locale.getDefault(), "%.3f°", sample.angle)
        detailText.text = String.format(
            Locale.getDefault(),
            "PRIVATE 65686   %.3f°",
            sample.angle,
        )
        rateText.text = if (updateHz.isFinite()) String.format(Locale.getDefault(), "%.1f Hz", updateHz) else "— Hz"
    }

    private fun requestLocalNetworkPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 37)
    }
}
