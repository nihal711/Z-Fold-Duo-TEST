package com.foldduo.hinge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellWakeLocksTest {
    private val listing = """
        Display 0, wakelock type: FULL_WAKE_LOCK: held=true refCount=2
        Display 1, wakelock type: FULL_WAKE_LOCK: held=false refCount=0
        Display 2, wakelock type: FULL_WAKE_LOCK: held=true refCount=1
        Display 3, wakelock type: SCREEN_BRIGHT_WAKE_LOCK: held=true refCount=1
    """.trimIndent()

    @Test
    fun listsOnlyHeldFullWakeLocks() {
        assertEquals(setOf(0, 2), ShellWakeLocks.heldDisplays(listing))
    }

    @Test
    fun heldCheckIsPerDisplay() {
        assertTrue(ShellWakeLocks.isHeld(listing, 0))
        assertFalse(ShellWakeLocks.isHeld(listing, 1))
        assertFalse(ShellWakeLocks.isHeld(listing, 3))
    }

    @Test
    fun emptyOrUnexpectedOutputHoldsNothing() {
        assertTrue(ShellWakeLocks.heldDisplays("").isEmpty())
        assertTrue(ShellWakeLocks.heldDisplays("Unknown command: set-wakelock").isEmpty())
    }
}
