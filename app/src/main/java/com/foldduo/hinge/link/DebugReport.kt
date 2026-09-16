package com.foldduo.hinge.link

import android.content.Context
import android.os.Build
import com.foldduo.hinge.AngleRuntime
import com.foldduo.hinge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            appendLine("wallpaper command: ${AngleRuntime.wallpaperCommand}")
            appendLine()
            val tx = AngleRuntime.wallpaperCommand.transaction
            if (!AngleRuntime.displayControlAvailable) {
                appendLine("== Shell sections skipped: ADB link is down")
            } else {
                // Most valuable first: the paste often gets cut off.
                shellSection("ZFoldDuo log (ZFoldDuoEngine, last 200 lines)",
                    "logcat -d -v time -t 200 -s ZFoldDuoEngine:V 2>&1 | grep -vE 'live frames display|stream ended \\(exit\\); restart #[0-9]{2,}'")
                shellSection("Probe command ($tx) reply",
                    "${WallpaperCommand.single(tx, "zfoldduo_report")} 2>&1")
                shellSection("Wallpaper process log after the probe (any tag, last 80 lines)",
                    "PID=\$(pidof com.samsung.android.wallpaper.live 2>/dev/null | cut -d' ' -f1); " +
                        "echo \"wallpaper pid=\$PID\"; sleep 0.3; " +
                        "[ -n \"\$PID\" ] && logcat -d -v brief -t 80 --pid=\$PID 2>&1")
                shellSection("FoldInteractive / mCurrentAngle lines (all tags, last 60)",
                    "logcat -d -v brief -t 6000 2>/dev/null | grep -E 'FoldInteractive|mCurrentAngle|zfoldduo_' | grep -v adbd | tail -n 60")
                shellSection("Crashes (AndroidRuntime, last 60 lines mentioning foldduo)",
                    "logcat -d -v time -t 3000 -s AndroidRuntime:E 2>&1 | grep -iE -A 10 'foldduo|LiveCaptureBridge' | tail -n 60")
                shellSection("Samsung build properties",
                    "getprop ro.build.version.oneui; getprop ro.build.version.sem; getprop ro.build.PDA; getprop ro.csc.sales_code")
                shellSection("Wallpaper service (component, lid state)",
                    "dumpsys wallpaper 2>&1 | grep -E 'mInfo.component|mWallpaperComponent|Lid state|mDefaultWallpaperComponent' | sort -u | head -n 12")
                shellSection("Device state", "cmd device_state print-state 2>&1; cmd device_state print-states 2>&1")
                shellSection("Displays", "dumpsys display 2>&1 | grep -oE 'DisplayViewport\\{[^}]*\\}' | head -n 6")
            }
            appendLine("== Hinge sensors visible to the app (registered candidates)")
            appendLine(AngleRuntime.describeSensors())
            appendLine()
            appendLine("== IWallpaperManager transaction codes on this build")
            appendLine(wallpaperTransactions(tx))
            appendLine()
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
    private fun wallpaperTransactions(probeTransaction: Int): String = runCatching {
        val entries = WallpaperCommand.transactionTable().entries.sortedBy { it.value }
        if (entries.isEmpty()) {
            "no TRANSACTION_ fields readable"
        } else {
            entries.joinToString("\n") { (name, code) ->
                val marker = if (code == probeTransaction) "   <== probe target" else ""
                "$code = $name$marker"
            }
        }
    }.getOrElse { error -> "unavailable: ${error.javaClass.simpleName}: ${error.message}" }
}
