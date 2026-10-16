package one.meshcore.shared.chat.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the single percent-decode used by mention paths and deep-link query items. */
class PercentDecodeOnceTest {
    @Test fun `a literal plus stays a plus`() {
        assertEquals("a+b", percentDecodeOnce("a+b"))
    }

    @Test fun `an encoded plus and slash decode once`() {
        assertEquals("a+b", percentDecodeOnce("a%2Bb"))
        assertEquals("a/b", percentDecodeOnce("a%2Fb"))
        assertEquals("%2F", percentDecodeOnce("%252F"))
    }

    @Test fun `utf-8 bytes decode as one string`() {
        assertEquals("é", percentDecodeOnce("%C3%A9"))
    }

    @Test fun `a malformed escape is null`() {
        assertNull(percentDecodeOnce("%"))
        assertNull(percentDecodeOnce("%2"))
        assertNull(percentDecodeOnce("%GG"))
        assertNull(percentDecodeOnce("ok%2"))
    }
}
