package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The registry is an LRU of [ChatCoordinator]s, matching iOS `ChatCoordinatorRegistry`. Repeat
 * [ChatCoordinatorRegistry.coordinator] reads promote; [ChatCoordinatorRegistry.existingCoordinator]
 * does not, so a closed-chat refresh can skip cold conversations without polluting the cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatCoordinatorRegistryTest {
    @Test
    fun `repeat coordinator reads share one instance and promote`() = runTest {
        val registry = ChatCoordinatorRegistry(FakeCoordinatorStore(), backgroundScope, capacity = 2)
        val first = id("1")
        val second = id("2")
        val a = registry.coordinator(first)
        registry.coordinator(second)
        assertSame(a, registry.coordinator(first))
        registry.coordinator(id("3"))
        assertSame(a, registry.existingCoordinator(first), "promoting first kept it off the eviction end")
        assertNull(registry.existingCoordinator(second))
    }

    @Test
    fun `existingCoordinator does not create or promote`() = runTest {
        val registry = ChatCoordinatorRegistry(FakeCoordinatorStore(), backgroundScope, capacity = 2)
        val first = id("1")
        assertNull(registry.existingCoordinator(first))
        registry.coordinator(first)
        registry.coordinator(id("2"))
        assertTrue(registry.existingCoordinator(first) != null)
        registry.coordinator(id("3"))
        assertNull(registry.existingCoordinator(first), "lookup without coordinator() must not save the oldest")
    }

    @Test
    fun `tearDown drops every entry`() = runTest {
        val registry = ChatCoordinatorRegistry(FakeCoordinatorStore(), backgroundScope, capacity = 2)
        val first = id("1")
        registry.coordinator(first)
        registry.tearDown()
        assertNull(registry.existingCoordinator(first))
    }

    @Test
    fun `remove drops only that conversation`() = runTest {
        val registry = ChatCoordinatorRegistry(FakeCoordinatorStore(), backgroundScope, capacity = 2)
        val first = id("1")
        val second = id("2")
        val kept = registry.coordinator(second)
        registry.coordinator(first)
        registry.remove(first)
        assertNull(registry.existingCoordinator(first))
        assertSame(kept, registry.existingCoordinator(second))
    }

    private fun id(suffix: String) = ChatConversationID.dm(
        ChatFixtures.RADIO,
        Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-${suffix.padStart(12, '0')}"),
    )
}
