package com.foldduo.hinge.effect

import org.junit.Assert.assertEquals
import org.junit.Test

class HingeTravelEstimatorTest {
    @Test
    fun openingIgnoresSmallBackwardJitter() {
        val tracker = HingeTravelEstimator()
        tracker.force(HingeTravel.OPENING, 75f)

        assertEquals(HingeTravel.OPENING, tracker.update(81f, 0L))
        assertEquals(HingeTravel.OPENING, tracker.update(80.2f, 200L))
        assertEquals(HingeTravel.OPENING, tracker.update(87f, 400L))
    }

    @Test
    fun reversalMustBeLargeAndSustained() {
        val tracker = HingeTravelEstimator()
        tracker.force(HingeTravel.OPENING, 171f)

        assertEquals(HingeTravel.OPENING, tracker.update(161f, 1_000L))
        assertEquals(HingeTravel.OPENING, tracker.update(158f, 1_050L))
        assertEquals(HingeTravel.CLOSING, tracker.update(154f, 1_081L))
    }

    @Test
    fun genuineReopenCancelsClosingDirection() {
        val tracker = HingeTravelEstimator()
        tracker.force(HingeTravel.CLOSING, 40f)

        assertEquals(HingeTravel.CLOSING, tracker.update(50f, 2_000L))
        assertEquals(HingeTravel.OPENING, tracker.update(55f, 2_081L))
    }

    @Test
    fun intermediateStartupWaitsForConfirmedDirection() {
        val tracker = HingeTravelEstimator()

        assertEquals(HingeTravel.UNKNOWN, tracker.update(100f, 0L))
        assertEquals(HingeTravel.UNKNOWN, tracker.update(90f, 100L))
        assertEquals(HingeTravel.CLOSING, tracker.update(87f, 181L))
    }
}
