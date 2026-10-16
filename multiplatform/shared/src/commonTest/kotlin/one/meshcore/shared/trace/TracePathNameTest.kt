package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors Swift `generatePathName`: hex-prefix fallback, single name, two-endpoint, and multi-endpoint. */
class TracePathNameTest {
    private fun name(names: List<String>, fallback: String): String = generateTracePathName(
        resolvedNames = names,
        fallbackHex = fallback,
        prefix = { "Path $it" },
        twoEndpoints = { a, b -> "$a -> $b" },
        multipleEndpoints = { a, b -> "$a -> ... -> $b" },
    )

    @Test
    fun noNamesUsesHexPrefixCappedAtEight() {
        assertEquals("Path 31A74F09", name(emptyList(), "31A74F0955AA"))
    }

    @Test
    fun singleNameIsUsedVerbatim() {
        assertEquals("Tower", name(listOf("Tower"), "31A7"))
    }

    @Test
    fun twoNamesJoinAsEndpoints() {
        assertEquals("Tower -> Ridge", name(listOf("Tower", "Ridge"), "31A7"))
    }

    @Test
    fun threeOrMoreNamesUseFirstAndLast() {
        assertEquals("Tower -> ... -> Ridge", name(listOf("Tower", "Mid", "Ridge"), "31A7"))
    }
}
