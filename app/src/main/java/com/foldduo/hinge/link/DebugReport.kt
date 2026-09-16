package com.foldduo.hinge.link

import android.content.Context
import android.os.Build
import com.foldduo.hinge.AngleRuntime
import com.foldduo.hinge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything needed to debug the private hinge stream and live capture on a
 * build we have never seen, gathered on the device itself through the ADB
 * link. The user shares the text; nobody needs a PC.
 */
object DebugReport {
    private const val MAX_SECTION_LINES = 160
    private const val MAX_SECTION_CHARS = 12_000

    suspend fun collect(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("ZFoldDuo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug report")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
            appendLine()
            appendLine("== Device")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}")
            appendLine("Locale ${Locale.getDefault()}")
            appendLine()
            appendLine("== Link")
            appendLine(AngleRuntime.status.value.toString())
            appendLine("angle source: ${AngleRuntime.sample.value?.source} angle=${AngleRuntime.sample.value?.angle}")
            appendLine("display control: ${AngleRuntime.displayControlAvailable}")
            appendLine("device states: ${AngleRuntime.deviceStates.value}")
            appendLine()
            appendLine("== Hinge sensors visible to the app (registered candidates)")
            appendLine(AngleRuntime.describeSensors())
            appendLine()
            appendLine("== IWallpaperManager transaction codes on this build")
            appendLine(wallpaperTransactions())
            appendLine()
            if (!AngleRuntime.displayControlAvailable) {
                appendLine("== Shell sections skipped: ADB link is down")
            } else {
                shellSection("Samsung build properties",
                    "getprop ro.build.version.oneui; getprop ro.build.version.sem; getprop ro.build.PDA; getprop ro.csc.sales_code; getprop ro.build.version.security_patch")
                shellSection("Wallpaper service (dumpsys wallpaper, head)", "dumpsys wallpaper 2>&1 | head -n 120")
                shellSection("FoldInteractive / SprWallpaper log lines already in the buffer",
                    "logcat -d -v brief -t 120 'SprWallpaper|FoldInteractive':I FoldInteractive:I SprWallpaper:I '*:S' 2>&1")
                shellSection("Private angle command (${PrivateAngleStream.PROBE_TRANSACTION}) result",
                    "service call wallpaper ${PrivateAngleStream.PROBE_TRANSACTION} i32 5 s16 ${PrivateAngleStream.ANGLE_ACTION} 2>&1; sleep 0.3; " +
                        "logcat -d -v brief -t 40 'SprWallpaper|FoldInteractive':I FoldInteractive:I SprWallpaper:I '*:S' 2>&1")
                shellSection("Any log line mentioning the probe action or mCurrentAngle (all tags, last 60)",
                    "logcat -d -v brief -t 4000 2>/dev/null | grep -E 'zfoldduo_angle|mCurrentAngle|FoldInteractive' | tail -n 60")
                shellSection("Sensor service: hinge-related sensors", "dumpsys sensorservice 2>&1 | grep -iE 'hinge|fold|angle' | head -n 60")
                shellSection("Device state", "cmd device_state print-state 2>&1; cmd device_state print-states 2>&1")
                shellSection("Displays", "dumpsys display 2>&1 | grep -E 'mDisplayId=|uniqueId=|state=|DisplayDeviceInfo|mBaseDisplayInfo' | head -n 60")
                shellSection("ZFoldDuo log (ZFoldDuoEngine, last 400 lines)", "logcat -d -v time -t 400 -s ZFoldDuoEngine:V 2>&1")
                shellSection("Crashes from the capture bridge or app (AndroidRuntime, last 80 lines)",
                    "logcat -d -v time -t 2000 -s AndroidRuntime:E 2>&1 | grep -iE -A 12 'foldduo|LiveCaptureBridge' | tail -n 80")
            }
        }
    }

    private fun StringBuilder.shellSection(title: String, command: String) {
        appendLine("== $title")
        appendLine("$ $command")
        val output = AngleRuntime.runShellForDiagnostics(command)
        appendLine(truncate(output ?: "<shell unavailable>"))
        appendLine()
    }

    private fun truncate(text: String): String {
        val lines = text.trimEnd().lines()
        val limited = if (lines.size > MAX_SECTION_LINES) {
            lines.take(MAX_SECTION_LINES) + "… (${lines.size - MAX_SECTION_LINES} more lines)"
        } else {
            lines
        }
        val joined = limited.joinToString("\n")
        return if (joined.length > MAX_SECTION_CHARS) joined.take(MAX_SECTION_CHARS) + "… (truncated)" else joined
    }

    /**
     * `service call wallpaper 90 …` targets whatever method sits at transaction
     * 90 in this build's IWallpaperManager. Listing the generated
     * TRANSACTION_* constants shows which method that is, and which code the
     * intended Samsung method has moved to.
     */
    private fun wallpaperTransactions(): String = runCatching {
        val stub = Class.forName("android.app.IWallpaperManager\$Stub")
        val entries = stub.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TRANSACTION_") && it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.getInt(null) to field.name.removePrefix("TRANSACTION_")
                }.getOrNull()
            }
            .sortedBy { it.first }
        if (entries.isEmpty()) {
            "no TRANSACTION_ fields readable (${stub.declaredFields.size} fields visible)"
        } else {
            entries.joinToString("\n") { (code, name) ->
                val marker = if (code == PrivateAngleStream.PROBE_TRANSACTION) "   <== probe target" else ""
                "$code = $name$marker"
            }
        }
    }.getOrElse { error -> "unavailable: ${error.javaClass.simpleName}: ${error.message}" }
}
