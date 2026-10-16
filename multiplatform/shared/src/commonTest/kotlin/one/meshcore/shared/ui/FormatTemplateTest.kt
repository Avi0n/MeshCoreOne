package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTemplateTest {
    @Test
    fun substitutesBareIntegerSpecifier() = assertEquals(
        "Enter the 64-character hexadecimal public key",
        formatTemplate("Enter the %d-character hexadecimal public key", listOf(64)),
    )

    @Test
    fun substitutesBareStringSpecifier() = assertEquals("Join #general", formatTemplate("Join #%s", listOf("general")))

    @Test
    fun consumesBareSpecifiersLeftToRight() = assertEquals(
        "Retrying 2/3",
        formatTemplate("Retrying %d/%d", listOf(2, 3)),
    )

    @Test
    fun mixesBareIntegerAndStringSequentially() = assertEquals(
        "Heard: 4 times",
        formatTemplate("Heard: %d %s", listOf(4, "times")),
    )

    @Test
    fun positionalSpecifiersSelectArgumentByIndex() = assertEquals(
        "b, from a, link",
        formatTemplate("%2\$s, from %1\$s, link", listOf("a", "b")),
    )

    @Test
    fun literalPercentIsUnescaped() = assertEquals("100% full", formatTemplate("100%% full", emptyList()))

    @Test
    fun leavesUnsupportedSpecifierIntact() = assertEquals("value %x", formatTemplate("value %x", listOf(1)))

    @Test
    fun leavesOutOfRangeSpecifierIntact() = assertEquals("%d", formatTemplate("%d", emptyList()))

    @Test
    fun substitutesQuickReplyFailedBody() = assertEquals(
        "Your reply to Alice couldn't be sent.",
        formatTemplate("Your reply to %s couldn't be sent.", listOf("Alice")),
    )

    @Test
    fun substitutesPositionalLowBatteryBody() = assertEquals(
        "Radio battery is at 15%",
        formatTemplate("%1\$s battery is at %2\$d%%", listOf("Radio", 15)),
    )
}
