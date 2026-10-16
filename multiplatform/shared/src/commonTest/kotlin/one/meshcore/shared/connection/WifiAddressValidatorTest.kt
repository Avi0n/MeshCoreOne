package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiAddressValidatorTest {
    @Test
    fun acceptsWellFormedIPv4Addresses() {
        assertTrue(WifiAddressValidator.isValidIPv4("192.168.1.50"))
        assertTrue(WifiAddressValidator.isValidIPv4("0.0.0.0"))
        assertTrue(WifiAddressValidator.isValidIPv4("255.255.255.255"))
    }

    @Test
    fun rejectsOutOfRangeOctets() {
        assertFalse(WifiAddressValidator.isValidIPv4("999.1.1.1"))
        assertFalse(WifiAddressValidator.isValidIPv4("256.0.0.1"))
        assertFalse(WifiAddressValidator.isValidIPv4("1.1.1.-1"))
    }

    @Test
    fun rejectsWrongOctetCount() {
        assertFalse(WifiAddressValidator.isValidIPv4("1.2.3"))
        assertFalse(WifiAddressValidator.isValidIPv4("1.2.3.4.5"))
        assertFalse(WifiAddressValidator.isValidIPv4(""))
    }

    @Test
    fun rejectsNonNumericOctets() {
        assertFalse(WifiAddressValidator.isValidIPv4("a.b.c.d"))
        assertFalse(WifiAddressValidator.isValidIPv4("192.168.1."))
    }

    @Test
    fun acceptsPortsWithinFirmwareRange() {
        assertTrue(WifiAddressValidator.isValidPort("1"))
        assertTrue(WifiAddressValidator.isValidPort("5000"))
        assertTrue(WifiAddressValidator.isValidPort("65535"))
    }

    @Test
    fun acceptsHostnamesAndIpv4() {
        assertTrue(WifiAddressValidator.isValidHost("192.168.1.50"))
        assertTrue(WifiAddressValidator.isValidHost("radio.local"))
        assertTrue(WifiAddressValidator.isValidHost("example.com"))
        assertTrue(WifiAddressValidator.isValidHost("repeater"))
        assertTrue(WifiAddressValidator.isValidHost("  radio.local  "))
    }

    @Test
    fun rejectsInvalidHosts() {
        assertFalse(WifiAddressValidator.isValidHost(""))
        assertFalse(WifiAddressValidator.isValidHost("999.999.999.999"))
        assertFalse(WifiAddressValidator.isValidHost("192.168.1"))
        assertFalse(WifiAddressValidator.isValidHost("http://radio.local"))
        assertFalse(WifiAddressValidator.isValidHost("-bad.local"))
    }

    @Test
    fun rejectsOutOfRangeOrNonNumericPorts() {
        assertFalse(WifiAddressValidator.isValidPort("0"))
        assertFalse(WifiAddressValidator.isValidPort("65536"))
        assertFalse(WifiAddressValidator.isValidPort("-1"))
        assertFalse(WifiAddressValidator.isValidPort(""))
        assertFalse(WifiAddressValidator.isValidPort("abc"))
    }

    @Test
    fun hostFieldReplacesCommaWithDotLikeIos() {
        assertEquals("192.168.1.50", WifiAddressValidator.sanitizeHostInput("192,168,1,50"))
        assertEquals("radio.local", WifiAddressValidator.sanitizeHostInput("radio.local"))
    }
}
