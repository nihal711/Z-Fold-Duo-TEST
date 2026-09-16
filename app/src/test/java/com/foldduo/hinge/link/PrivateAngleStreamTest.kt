package com.foldduo.hinge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAngleStreamTest {
    @Test
    fun parsesAngleFromFoldInteractiveLogLine() {
        val line = "I/FoldInteractive( 4321): onCommand: action[zfoldduo_angle], mCurrentAngle[123.456]"
        assertEquals(123.456f, PrivateAngleStream.parseAngle(line)!!, 0.0001f)
    }

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(0f, PrivateAngleStream.parseAngle("mCurrentAngle[-2.5]")!!, 0f)
        assertEquals(180f, PrivateAngleStream.parseAngle("mCurrentAngle[184]")!!, 0f)
    }

    @Test
    fun nonAngleLinesYieldNull() {
        assertNull(PrivateAngleStream.parseAngle("I/FoldInteractive( 4321): unregisterSensor: mIsSensorRegistered[true]"))
        assertNull(PrivateAngleStream.parseAngle(""))
        assertNull(PrivateAngleStream.parseAngle("mCurrentAngle[abc]"))
    }

    @Test
    fun detectsSensorStoppedMarker() {
        assertTrue(PrivateAngleStream.isSensorStopped("I/FoldInteractive( 4321): unregisterSensor: mIsSensorRegistered[true]"))
        assertFalse(PrivateAngleStream.isSensorStopped("I/FoldInteractive( 4321): registerSensor: mIsSensorRegistered[false]"))
    }
}
