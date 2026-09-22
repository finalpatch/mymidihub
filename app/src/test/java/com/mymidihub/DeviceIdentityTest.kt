package com.mymidihub

import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    @Test fun `identity is stable across process instances`() {
        val properties = listOf("1", "", "", "Maker", "Keyboard", "Keyboard", "Serial123", "2:0:Out")
        assertEquals(DeviceIdentity.key(properties), DeviceIdentity.key(properties.toList()))
        assertEquals(64, DeviceIdentity.key(properties).length)
    }

    @Test fun `serial number distinguishes otherwise identical devices`() {
        assertNotEquals(DeviceIdentity.key(listOf("Keyboard", "Serial1")), DeviceIdentity.key(listOf("Keyboard", "Serial2")))
    }

    @Test fun `embedded separators cannot alias another field boundary`() {
        assertNotEquals(DeviceIdentity.key(listOf("ab", "c")), DeviceIdentity.key(listOf("a", "bc")))
        assertNotEquals(DeviceIdentity.key(listOf("a:b", "c")), DeviceIdentity.key(listOf("a", "b:c")))
    }
}
