package one.meshcore.android

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRepeatChromeTest {
    @Test
    fun aVerifiedEnablePublishesBeforeTheNextConnectionRung() = runTest {
        var published = false
        stampClientRepeatAfterVerifiedWrite(
            persist = { },
            publish = { published = it },
            enabled = true,
        )
        assertTrue(published)
    }

    @Test
    fun aVerifiedDisablePublishesBeforeTheNextConnectionRung() = runTest {
        var published = true
        stampClientRepeatAfterVerifiedWrite(
            persist = { },
            publish = { published = it },
            enabled = false,
        )
        assertFalse(published)
    }

    @Test
    fun aFailedPersistDoesNotPublish() = runTest {
        var published: Boolean? = null
        runCatching {
            stampClientRepeatAfterVerifiedWrite(
                persist = { error("radio refused") },
                publish = { published = it },
                enabled = true,
            )
        }
        assertEquals(null, published)
    }
}
