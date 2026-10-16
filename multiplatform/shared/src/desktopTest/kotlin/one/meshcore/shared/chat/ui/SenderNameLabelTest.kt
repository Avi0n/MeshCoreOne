package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trust qualification the channel sender line renders. A channel sender is never cryptographically
 * verified, so an asserted nickname and a short-prefix guess both have to be marked; a name that came
 * straight off the wire or from a unique key match renders plain. Mirrors the Swift `SenderNameLabel`
 * branches and `FallbackMatchIndicatorView`.
 */
class SenderNameLabelTest {
    @Test
    fun nameOnlyNicknameMatchIsMarkedUnverified() {
        val resolution = NodeNameResolution(
            displayName = "Alpha",
            matchKind = NodeNameMatchKind.EXACT,
            unverifiedNickname = "Rico",
        )

        assertEquals(SenderLabelTrust.UNVERIFIED_NICKNAME, senderLabelTrust(resolution))
    }

    @Test
    fun prefixCollisionIsMarkedAPossibleMatch() {
        val resolution = NodeNameResolution(displayName = "Charlie", matchKind = NodeNameMatchKind.FALLBACK)

        assertEquals(SenderLabelTrust.POSSIBLE_MATCH, senderLabelTrust(resolution))
    }

    @Test
    fun aPlainWireNameCarriesNoMarker() {
        assertEquals(
            SenderLabelTrust.PLAIN,
            senderLabelTrust(NodeNameResolution(displayName = "Bravo", matchKind = NodeNameMatchKind.EXACT)),
        )
        assertEquals(
            SenderLabelTrust.PLAIN,
            senderLabelTrust(NodeNameResolution(displayName = "ABCD", matchKind = NodeNameMatchKind.UNRESOLVED)),
        )
    }

    @Test
    fun anAssertedNicknameOutranksThePossibleMatchMarker() {
        val resolution = NodeNameResolution(
            displayName = "Alpha",
            matchKind = NodeNameMatchKind.FALLBACK,
            unverifiedNickname = "Rico",
        )

        assertEquals(SenderLabelTrust.UNVERIFIED_NICKNAME, senderLabelTrust(resolution))
    }
}
