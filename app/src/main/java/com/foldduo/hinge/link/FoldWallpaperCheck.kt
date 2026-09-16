package com.foldduo.hinge.link

/**
 * Samsung's fine hinge angle only leaks through `FoldInteractive`, the engine
 * of the stock Folding (interactive video) wallpaper. Any other wallpaper,
 * for example the Layered wallpaper set through Wallpaper and style, accepts
 * the command and logs nothing. These helpers decide which situation we are in
 * from shell output so the UI can say so instead of showing "waiting" forever.
 */
object FoldWallpaperCheck {
    const val WALLPAPER_PACKAGE = "com.samsung.android.wallpaper.live"
    const val ENGINE_TAG = "FoldInteractive"

    /** Component of Samsung's stock Folding wallpaper (its engine is FoldInteractive). */
    const val FOLD_INTERACTIVE_COMPONENT = "$WALLPAPER_PACKAGE/$WALLPAPER_PACKAGE.fold.FoldInteractive"

    /** True when any wallpaper slot in `dumpsys wallpaper` runs the Folding wallpaper. */
    fun isFoldInteractive(dumpsysWallpaper: String): Boolean =
        dumpsysWallpaper.contains(FOLD_INTERACTIVE_COMPONENT)

    /** `logcat --pid=<wallpaper pid>` output for the recent past, after a few probes. */
    fun logCommand(pidExpression: String = "\$(pidof $WALLPAPER_PACKAGE | cut -d' ' -f1)"): String =
        "logcat -d -v brief --pid=$pidExpression 2>/dev/null | grep -c '$ENGINE_TAG'"

    /** Home-screen wallpaper component from `dumpsys wallpaper`, or null. */
    fun homeComponent(dumpsysWallpaper: String): String? =
        COMPONENT.findAll(dumpsysWallpaper)
            .map { it.groupValues[1] }
            .firstOrNull { it != IMAGE_WALLPAPER }
            ?: COMPONENT.find(dumpsysWallpaper)?.groupValues?.get(1)

    /** True when the FoldInteractive engine logged at least once (grep -c output). */
    fun engineSeen(grepCountOutput: String): Boolean =
        grepCountOutput.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } == true

    fun describeMissing(component: String?): String =
        "wallpaper engine ${component?.substringAfterLast('.') ?: "unknown"} has no $ENGINE_TAG; set the Samsung Folding wallpaper"

    private val COMPONENT = Regex("mWallpaperComponent=ComponentInfo\\{([^}]+)\\}")
    private const val IMAGE_WALLPAPER = "com.android.systemui/com.android.systemui.wallpapers.ImageWallpaper"
}
