package com.foldduo.hinge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperCommandTest {
    @Test
    fun wrongMethodReplyIsRejected() {
        // Verbatim reply from a Fold8 Ultra (One UI 9) where 90 is getWallpaperBackgroundRegion.
        val reply = """
            Result: Parcel(
            0x00000000: fffffffe 0000002f 00610050 00630072 '..../...P.a.r.c.'
            0x00000010: 006c0065 00640020 00740061 00200061 'e.l. .d.a.t.a. .'
            0x00000020: 006f006e 00200074 00750066 006c006c 'n.o.t. .f.u.l.l.'
            0x00000030: 00200079 006f0063 0073006e 006d0075 'y. .c.o.n.s.u.m.'
            0x00000040: 00640065 0020002c 006e0075 00650072 'e.d.,. .u.n.r.e.'
            0x00000050: 00640061 00730020 007a0069 003a0065 'a.d. .s.i.z.e.:.'
            0x00000060: 00320020 00000034 00000000          ' .2.4.......    ')
        """.trimIndent()
        assertFalse(WallpaperCommand.isCleanReply(reply))
    }

    @Test
    fun emptyParcelIsAccepted() {
        assertTrue(WallpaperCommand.isCleanReply("Result: Parcel(00000000    '....')"))
    }

    @Test
    fun exceptionsAndMissingServicesAreRejected() {
        assertFalse(WallpaperCommand.isCleanReply("Result: Parcel(\n  0x00000000: ffffffff ... 'java.lang.SecurityException')"))
        assertFalse(WallpaperCommand.isCleanReply("service: Service wallpaper does not exist"))
        assertFalse(WallpaperCommand.isCleanReply(""))
    }

    @Test
    fun commandsEmbedTheResolvedTransaction() {
        assertEquals(
            "service call wallpaper 92 i32 5 s16 zfoldduo_angle",
            WallpaperCommand.single(92, PrivateAngleStream.ANGLE_ACTION),
        )
        assertTrue(WallpaperCommand.probeLoop(92).startsWith("while :; do service call wallpaper 92 i32 5 s16 zfoldduo_angle"))
        assertTrue(WallpaperCommand.wake(92).contains("i32 5 s16 android.wallpaper.wakingup"))
    }
}
