package one.meshcore.shared.urlsafety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial IP-range vectors for [IpAddressClassifier], mirroring the Swift
 * `URLSafetyCheckerTests` private/reserved cases plus obfuscation vectors that must NOT be treated
 * as recognizable literals (they defer to DNS, which re-checks the resolved address).
 */
class IpAddressClassifierTest {
    private fun assertPrivate(address: String) =
        assertTrue(IpAddressClassifier.isPrivateOrReserved(address), "$address should be private/reserved")

    private fun assertPublic(address: String) =
        assertFalse(IpAddressClassifier.isPrivateOrReserved(address), "$address should be public / not a literal")

    // MARK: - IPv4 private/reserved ranges

    @Test
    fun detectsLoopbackV4() {
        assertPrivate("127.0.0.1")
        assertPrivate("127.255.255.255")
    }

    @Test
    fun detectsPrivateClassA() {
        assertPrivate("10.0.0.1")
        assertPrivate("10.255.255.255")
    }

    @Test
    fun detectsPrivateClassB() {
        assertPrivate("172.16.0.1")
        assertPrivate("172.31.255.255")
        assertPublic("172.32.0.1") // just above the /12
        assertPublic("172.15.255.255") // just below the /12
    }

    @Test
    fun detectsPrivateClassC() {
        assertPrivate("192.168.0.1")
        assertPrivate("192.168.255.255")
    }

    @Test
    fun detectsLinkLocalV4() {
        assertPrivate("169.254.1.1")
        assertPrivate("169.254.169.254") // AWS/GCP metadata endpoint
    }

    @Test
    fun detectsUnspecifiedV4() {
        assertPrivate("0.0.0.0")
    }

    @Test
    fun detectsMulticastV4() {
        assertPrivate("224.0.0.1")
        assertPrivate("239.255.255.255")
    }

    @Test
    fun detectsReservedV4() {
        assertPrivate("240.0.0.1")
        assertPrivate("255.255.255.254")
        assertPrivate("255.255.255.255")
    }

    @Test
    fun allowsPublicV4() {
        assertPublic("8.8.8.8")
        assertPublic("1.1.1.1")
        assertPublic("93.184.216.34") // example.com
    }

    // MARK: - IPv6 private/reserved ranges

    @Test
    fun detectsLoopbackV6() {
        assertPrivate("::1")
    }

    @Test
    fun detectsUnspecifiedV6() {
        assertPrivate("::")
    }

    @Test
    fun detectsLinkLocalV6() {
        assertPrivate("fe80::1")
        assertPrivate("fe80::abcd:ef01:2345:6789")
        assertPrivate("febf::1") // top of fe80::/10
    }

    @Test
    fun detectsUniqueLocalV6() {
        assertPrivate("fc00::1")
        assertPrivate("fd00::1")
    }

    @Test
    fun detectsMappedV4() {
        assertPrivate("::ffff:127.0.0.1")
        assertPrivate("::ffff:192.168.1.1")
        assertPrivate("::ffff:10.0.0.1")
        assertPublic("::ffff:8.8.8.8") // mapped public passes through
    }

    @Test
    fun detectsMulticastV6() {
        assertPrivate("ff02::1")
        assertPrivate("ff05::2")
    }

    @Test
    fun allowsPublicV6() {
        assertPublic("2001:4860:4860::8888") // Google DNS v6
        assertPublic("2606:4700:4700::1111") // Cloudflare v6
    }

    // MARK: - Non-IP hostnames

    @Test
    fun nonIpStringsAreNotLiterals() {
        assertPublic("example.com")
        assertPublic("localhost")
        assertPublic("")
    }

    // MARK: - Obfuscation vectors (not literals here; deferred to DNS re-check)

    @Test
    fun obfuscatedFormsAreNotTreatedAsLiterals() {
        assertPublic("2130706433") // decimal 127.0.0.1
        assertPublic("0x7f000001") // hex 127.0.0.1
        assertPublic("0177.0.0.1") // octal-looking leading zero
        assertPublic("127.1") // short form
        assertPublic("127.0.0.1.5") // too many octets
        assertPublic("256.0.0.1") // octet out of range
        assertPublic("192.168.1.-1") // negative octet
        assertPublic("fe80::1%eth0") // zone id rejected
        assertPublic("1:2:3:4:5:6:7:8:9") // too many hextets
        assertPublic("gggg::1") // non-hex
    }
}
