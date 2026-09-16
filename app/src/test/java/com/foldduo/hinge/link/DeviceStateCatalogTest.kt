package com.foldduo.hinge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStateCatalogTest {
    private val fold7PrintStates = """
        Supported states: [
          DeviceState{identifier=0, name='CLOSED', app_accessible=true, cancel_when_requester_not_on_top=false},
          DeviceState{identifier=1, name='HALF_OPENED', app_accessible=true, cancel_when_requester_not_on_top=false},
          DeviceState{identifier=2, name='OPENED', app_accessible=true, cancel_when_requester_not_on_top=false},
          DeviceState{identifier=4, name='CONCURRENT_INNER_DEFAULT', app_accessible=false, cancel_when_requester_not_on_top=false},
          DeviceState{identifier=5, name='CONCURRENT_OUTER_DEFAULT', app_accessible=false, cancel_when_requester_not_on_top=false},
        ]
    """.trimIndent()

    @Test
    fun resolvesSamsungStateIdentifiersByName() {
        val catalog = DeviceStateCatalog.parse(fold7PrintStates)
        assertEquals(0, catalog.closed)
        assertEquals(4, catalog.concurrentInnerDefault)
        assertEquals(5, catalog.concurrentOuterDefault)
        assertTrue(catalog.supportsConcurrentDisplays)
        assertEquals("CONCURRENT_OUTER_DEFAULT", catalog.nameOf(5))
    }

    @Test
    fun differentIdentifiersOnAnotherModelAreHonoured() {
        val output = """
            Supported states: [
              DeviceState{identifier=0, name='CLOSED'},
              DeviceState{identifier=1, name='HALF_OPENED'},
              DeviceState{identifier=2, name='OPENED'},
              DeviceState{identifier=3, name='REAR_DISPLAY'},
              DeviceState{identifier=6, name='CONCURRENT_INNER_DEFAULT'},
              DeviceState{identifier=7, name='CONCURRENT_OUTER_DEFAULT'},
            ]
        """.trimIndent()
        val catalog = DeviceStateCatalog.parse(output)
        assertEquals(6, catalog.concurrentInnerDefault)
        assertEquals(7, catalog.concurrentOuterDefault)
    }

    @Test
    fun fallsBackToKnownFold7IdentifiersWhenOutputIsUnusable() {
        val catalog = DeviceStateCatalog.parse("cmd: Can't find service: device_state")
        assertTrue(catalog.isEmpty)
        assertFalse(catalog.supportsConcurrentDisplays)
        assertEquals(DeviceStateCatalog.DEFAULT_CLOSED, catalog.closed)
        assertEquals(DeviceStateCatalog.DEFAULT_CONCURRENT_INNER, catalog.concurrentInnerDefault)
        assertEquals(DeviceStateCatalog.DEFAULT_CONCURRENT_OUTER, catalog.concurrentOuterDefault)
    }

    @Test
    fun toleratesSpacingVariationsInNewerDumps() {
        val output = "DeviceState{ identifier = 5 , name = 'CONCURRENT_OUTER_DEFAULT' , properties=[...] }"
        assertEquals(5, DeviceStateCatalog.parse(output).concurrentOuterDefault)
    }

    @Test
    fun readsBaseAndCommittedStatesFromDumpsys() {
        val dump = """
            DEVICE STATE MANAGER (dumpsys device_state)
              mCommittedState=Optional[DeviceState{identifier=5, name='CONCURRENT_OUTER_DEFAULT', app_accessible=false}]
              mPendingState=Optional.empty
              mBaseState=Optional[DeviceState{identifier=0, name='CLOSED', app_accessible=true}]
              mOverrideState=Optional[DeviceState{identifier=5, name='CONCURRENT_OUTER_DEFAULT', app_accessible=false}]
        """.trimIndent()
        assertEquals(0 to "CLOSED", DeviceStateCatalog.baseState(dump))
        assertEquals(5 to "CONCURRENT_OUTER_DEFAULT", DeviceStateCatalog.committedState(dump))
    }

    @Test
    fun readsStatesWhenOptionalWrapperIsAbsent() {
        val dump = "  mBaseState=DeviceState{identifier=2, name='OPENED'}\n  mCommittedState=DeviceState{identifier=2, name='OPENED'}"
        assertEquals(2 to "OPENED", DeviceStateCatalog.baseState(dump))
        assertEquals(2 to "OPENED", DeviceStateCatalog.committedState(dump))
    }

    @Test
    fun missingFieldsReturnNull() {
        assertNull(DeviceStateCatalog.baseState("mBaseState=Optional.empty"))
        assertNull(DeviceStateCatalog.committedState(""))
    }
}
