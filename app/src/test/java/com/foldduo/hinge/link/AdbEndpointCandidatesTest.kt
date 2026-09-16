package com.foldduo.hinge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbEndpointCandidatesTest {
    @Test
    fun loopbackWithMdnsPortComesFirstAndWifiAddressIsLast() {
        val candidates = AdbEndpointCandidates.build(
            mdnsHost = "192.168.1.23",
            mdnsPort = 40123,
            propertyPort = 40123,
            rememberedPort = 39001,
        )
        assertEquals(
            listOf(
                AdbEndpoint("127.0.0.1", 40123, AdbEndpoint.Origin.MDNS),
                AdbEndpoint("127.0.0.1", 39001, AdbEndpoint.Origin.REMEMBERED),
                AdbEndpoint("192.168.1.23", 40123, AdbEndpoint.Origin.MDNS),
            ),
            candidates,
        )
    }

    @Test
    fun rememberedPortAloneStillProducesACandidate() {
        val candidates = AdbEndpointCandidates.build(null, null, null, 41000)
        assertEquals(listOf(AdbEndpoint("127.0.0.1", 41000, AdbEndpoint.Origin.REMEMBERED)), candidates)
    }

    @Test
    fun propertyPortIsUsedWhenMdnsIsSilent() {
        val candidates = AdbEndpointCandidates.build(null, null, 37777, null)
        assertEquals(AdbEndpoint.Origin.SYSTEM_PROPERTY, candidates.single().origin)
        assertEquals(37777, candidates.single().port)
    }

    @Test
    fun nothingKnownYieldsNoCandidates() {
        assertTrue(AdbEndpointCandidates.build(null, null, null, null).isEmpty())
        assertTrue(AdbEndpointCandidates.build("192.168.0.2", null, null, null).isEmpty())
    }

    @Test
    fun invalidPortsAreIgnored() {
        assertTrue(AdbEndpointCandidates.build(null, 0, 70000, -1).isEmpty())
    }

    @Test
    fun loopbackMdnsHostIsNotDuplicated() {
        val candidates = AdbEndpointCandidates.build("127.0.0.1", 5555, null, null)
        assertEquals(1, candidates.size)
    }

    @Test
    fun parsesPropertyPortLeniently() {
        assertEquals(43210, AdbEndpointCandidates.parsePropertyPort("43210\n"))
        assertNull(AdbEndpointCandidates.parsePropertyPort(""))
        assertNull(AdbEndpointCandidates.parsePropertyPort("   "))
        assertNull(AdbEndpointCandidates.parsePropertyPort("0"))
        assertNull(AdbEndpointCandidates.parsePropertyPort("getprop: not found"))
        assertNull(AdbEndpointCandidates.parsePropertyPort(null))
    }
}
