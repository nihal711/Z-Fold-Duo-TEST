package com.foldduo.hinge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldWallpaperCheckTest {
    private val dump = """
        mDefaultWallpaperComponent=ComponentInfo{com.android.systemui/com.android.systemui.wallpapers.ImageWallpaper}
          mWallpaperComponent=ComponentInfo{com.android.systemui/com.android.systemui.wallpapers.ImageWallpaper}
          mWallpaperComponent=ComponentInfo{com.samsung.android.wallpaper.live/com.samsung.android.wallpaper.live.layered.LayeredWallpaperService}
    """.trimIndent()

    @Test
    fun prefersTheNonDefaultComponent() {
        assertEquals(
            "com.samsung.android.wallpaper.live/com.samsung.android.wallpaper.live.layered.LayeredWallpaperService",
            FoldWallpaperCheck.homeComponent(dump),
        )
    }

    @Test
    fun recognisesTheFoldingWallpaperComponent() {
        assertFalse(FoldWallpaperCheck.isFoldInteractive(dump))
        val withFold = dump + "\n  mWallpaperComponent=ComponentInfo{com.samsung.android.wallpaper.live/com.samsung.android.wallpaper.live.fold.FoldInteractive}"
        assertTrue(FoldWallpaperCheck.isFoldInteractive(withFold))
    }

    @Test
    fun engineSeenParsesGrepCount() {
        assertTrue(FoldWallpaperCheck.engineSeen("12\n"))
        assertFalse(FoldWallpaperCheck.engineSeen("0"))
        assertFalse(FoldWallpaperCheck.engineSeen(""))
        assertFalse(FoldWallpaperCheck.engineSeen("logcat: unexpected"))
    }

    @Test
    fun missingDescriptionNamesTheEngine() {
        val text = FoldWallpaperCheck.describeMissing(FoldWallpaperCheck.homeComponent(dump))
        assertTrue(text.contains("LayeredWallpaperService"))
        assertTrue(text.contains("FoldInteractive"))
    }
}
