package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.chat.ChatThreadRenderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatThreadScrollEffectsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nearBottomReporterUsesTheLatestCallback() {
        var useSecondCallback by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            val listState = rememberLazyListState()
            val callback: (Boolean) -> Unit = if (useSecondCallback) {
                { calls += SECOND_CALLBACK }
            } else {
                { calls += FIRST_CALLBACK }
            }
            TestList(listState = listState)
            NearBottomReporter(listState = listState, onChanged = callback)
        }

        compose.runOnIdle { useSecondCallback = true }
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(LAST_ITEM_INDEX)
        compose.runOnIdle {
            assertTrue(calls.contains(FIRST_CALLBACK))
            assertEquals(SECOND_CALLBACK, calls.last())
        }
    }

    @Test
    fun nearTopPaginationUsesTheLatestFlagsAndCallback() {
        var paginationEnabled by mutableStateOf(false)
        var useSecondCallback by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            val listState = rememberLazyListState()
            val callback = if (useSecondCallback) {
                { calls += SECOND_CALLBACK }
            } else {
                { calls += FIRST_CALLBACK }
            }
            TestList(listState = listState)
            NearTopPaginationTrigger(
                listState = listState,
                hasMore = paginationEnabled,
                isLoadingOlder = !paginationEnabled,
                onNearTop = callback,
            )
        }

        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(LAST_ITEM_INDEX)
        compose.runOnIdle {
            paginationEnabled = true
            useSecondCallback = true
        }
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(0)
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(LAST_ITEM_INDEX)
        compose.runOnIdle { assertEquals(listOf(SECOND_CALLBACK), calls) }
    }

    @Test
    fun scrollAffordancesUseTheLatestCallbacks() {
        var useSecondCallback by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            val listState = rememberLazyListState()
            val callback: (Boolean) -> Unit = if (useSecondCallback) {
                { calls += SECOND_CALLBACK }
            } else {
                { calls += FIRST_CALLBACK }
            }
            TestList(listState = listState)
            ThreadScrollAffordanceEffects(
                listState = listState,
                state = ChatThreadRenderState.EMPTY,
                onMentionSeen = {},
                onDividerVisibleChanged = callback,
                onOffscreenMentionsChanged = {},
            )
        }

        compose.runOnIdle { useSecondCallback = true }
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(LAST_ITEM_INDEX)
        compose.runOnIdle {
            assertTrue(calls.contains(FIRST_CALLBACK))
            assertEquals(SECOND_CALLBACK, calls.last())
        }
    }

    @Composable
    private fun TestList(listState: LazyListState) {
        LazyColumn(
            state = listState,
            modifier = Modifier.height(LIST_HEIGHT).testTag(LIST_TAG),
        ) {
            items(TEST_ITEMS, key = { it.toString() }) { Text(it.toString()) }
        }
    }

    private companion object {
        const val FIRST_CALLBACK = "first"
        const val SECOND_CALLBACK = "second"
        const val LIST_TAG = "chat-thread-test-list"
        const val LAST_ITEM_INDEX = 99
        val LIST_HEIGHT = 120.dp
        val TEST_ITEMS = List(LAST_ITEM_INDEX + 1) { it }
    }
}
