package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Contact-detail reload faults that must keep the thrown instance. A persist exception still keeps
 * the open row in the existing detail tests.
 */
class ContactDetailReloadFaultTest {
    @Test
    fun aCancelledReloadRethrowsTheSameException() = runTest {
        val cancelled = CancellationException("superseded")
        val open = detailContact()
        val thrown = assertFailsWith<CancellationException> {
            ContactDetailLogic.reloadContact(open) { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aReloadErrorRethrowsTheSameError() = runTest {
        val failure = DetailReloadProbeError()
        val open = detailContact()
        val thrown = assertFailsWith<DetailReloadProbeError> {
            ContactDetailLogic.reloadContact(open) { throw failure }
        }
        assertSame(failure, thrown)
    }
}

private class DetailReloadProbeError : Error("probe")

private fun detailContact() = contactDto(
    id = ContactTestIds.fresh(),
    radioID = ContactTestIds.RADIO,
    keyByte = 0x01,
    name = "Alice",
    typeRawValue = ContactType.CHAT.raw,
)
