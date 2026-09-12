package com.foldduo.hinge.effect

import org.junit.Assert.assertEquals
import org.junit.Test

class HingeProjectionTest {
    private val config = EffectProfile(intensity = 1f)

    @Test
    fun bothFacesReachTheSameEdgeOnPoseAtNinetyDegrees() {
        assertEquals(HingeProjection.MAX_TILT, HingeProjection.coverTiltForHinge(90f, config), 0.0001f)
        assertEquals(HingeProjection.MAX_TILT, HingeProjection.tiltForHinge(90f, config), 0.0001f)
    }

    @Test
    fun coverAndInnerAreOppositeFacesOfTheSameMovingLeaf() {
        listOf(1f, 20f, 45f, 70f, 89f).forEach { hinge ->
            assertEquals(
                "hinge=$hinge",
                HingeProjection.coverTiltForHinge(hinge, config),
                HingeProjection.tiltForHinge(180f - hinge, config),
                0.0001f,
            )
        }
    }

    @Test
    fun visibleFaceUsesTheFullPhysicalHingeAngle() {
        assertEquals(30f, HingeProjection.coverTiltForHinge(30f, config), 0.0001f)
        assertEquals(30f, HingeProjection.tiltForHinge(150f, config), 0.0001f)
        assertEquals(75f, HingeProjection.coverTiltForHinge(75f, config), 0.0001f)
        assertEquals(75f, HingeProjection.tiltForHinge(105f, config), 0.0001f)
    }

    @Test
    fun activePanelIsFlatAtItsPhysicalEndpoint() {
        assertEquals(0f, HingeProjection.coverTiltForHinge(0f, config), 0.0001f)
        assertEquals(0f, HingeProjection.tiltForHinge(180f, config), 0.0001f)
    }
}
