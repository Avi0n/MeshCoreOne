package one.meshcore.shared.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the pure consumer helpers that mirror the Swift `CLIToolViewModel` completion extension:
 * ghost-text derivation, applying a completion by replacing the final token, and the Tab cycle
 * index arithmetic.
 */
class CLICompletionConsumerTest {
    // MARK: - ghostSuffix

    @Test
    fun ghostSuffixIsTheRemainderOfTheFirstSuggestion() {
        assertEquals("p", CLICompletionConsumer.ghostSuffix("hel", listOf("help")))
    }

    @Test
    fun ghostSuffixCompletesTheFinalArgumentToken() {
        assertEquals("st", CLICompletionConsumer.ghostSuffix("session li", listOf("list", "local")))
    }

    @Test
    fun ghostSuffixIsWholeSuggestionAfterTrailingSpace() {
        assertEquals("list", CLICompletionConsumer.ghostSuffix("session ", listOf("list", "local")))
    }

    @Test
    fun ghostSuffixIsEmptyWhenNoSuggestions() {
        assertEquals("", CLICompletionConsumer.ghostSuffix("hel", emptyList()))
    }

    @Test
    fun ghostSuffixIsEmptyWhenFirstSuggestionDoesNotExtendLastToken() {
        // Node names may not share the typed prefix once the list is sorted; guard against it.
        assertEquals("", CLICompletionConsumer.ghostSuffix("login z", listOf("Alpha")))
    }

    @Test
    fun ghostSuffixMatchesCaseInsensitively() {
        assertEquals("p", CLICompletionConsumer.ghostSuffix("HEL", listOf("help")))
    }

    // MARK: - applyCompletion

    @Test
    fun applyCompletionOnSingleTokenAppendsTrailingSpace() {
        assertEquals("help ", CLICompletionConsumer.applyCompletion("hel", "help"))
    }

    @Test
    fun applyCompletionReplacesFinalArgumentToken() {
        assertEquals("session list ", CLICompletionConsumer.applyCompletion("session li", "list"))
    }

    @Test
    fun applyCompletionOnEmptyInputAppendsTrailingSpace() {
        assertEquals("help ", CLICompletionConsumer.applyCompletion("", "help"))
    }

    @Test
    fun applyCompletionKeepsEarlierTokens() {
        assertEquals(
            "set loop.detect minimal ",
            CLICompletionConsumer.applyCompletion("set loop.detect m", "minimal"),
        )
    }

    // MARK: - nextTabIndex

    @Test
    fun nextTabIndexStartsAtZeroWhenNothingHighlighted() {
        assertEquals(0, CLICompletionConsumer.nextTabIndex(currentIndex = null, count = 3))
    }

    @Test
    fun nextTabIndexAdvances() {
        assertEquals(1, CLICompletionConsumer.nextTabIndex(currentIndex = 0, count = 3))
        assertEquals(2, CLICompletionConsumer.nextTabIndex(currentIndex = 1, count = 3))
    }

    @Test
    fun nextTabIndexWrapsAround() {
        assertEquals(0, CLICompletionConsumer.nextTabIndex(currentIndex = 2, count = 3))
    }
}
