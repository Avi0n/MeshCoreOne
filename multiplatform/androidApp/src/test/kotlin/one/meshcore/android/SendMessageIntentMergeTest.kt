package one.meshcore.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendMessageIntentMergeTest {
    @Test
    fun missingTextIsFilledFromTheExtra() {
        val merged = mergeSendMessageText(
            "meshcore://send?kind=contact&id=A0000000-0000-0000-0000-000000000001&name=Alice",
            extraText = "hello",
        )
        assertEquals(
            "meshcore://send?kind=contact&id=A0000000-0000-0000-0000-000000000001&name=Alice&text=hello",
            merged,
        )
    }

    @Test
    fun existingTextIsLeftAlone() {
        val url = "meshcore://send?kind=contact&id=A0000000-0000-0000-0000-000000000001&text=keep"
        assertEquals(url, mergeSendMessageText(url, extraText = "other"))
    }

    @Test
    fun anotherParameterEndingInTextDoesNotHideTheAssistantText() {
        val url = "meshcore://send?kind=contact&id=A0000000-0000-0000-0000-000000000001&context=brief"
        assertEquals("$url&text=hello", mergeSendMessageText(url, extraText = "hello"))
    }

    @Test
    fun nonSendUrlsPassThrough() {
        assertEquals("meshcore://chats", mergeSendMessageText("meshcore://chats", extraText = "hello"))
        assertNull(mergeSendMessageText(null, extraText = "hello"))
    }
}
