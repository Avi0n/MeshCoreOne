package one.meshcore.android

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertFalse
import org.junit.Test

class FloodScopePublicationGateTest {
    @Test
    fun mutationInvalidatesAnOlderLoad() = runTest {
        val gate = FloodScopePublicationGate()
        val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val generation = gate.capture()
        var published = false

        gate.invalidate()

        assertFalse(
            gate.publishLoaded(generation, radio, currentRadio = { radio }) {
                published = true
            },
        )
        assertFalse(published)
    }

    @Test
    fun radioReplacementRejectsAnOlderMutation() = runTest {
        val gate = FloodScopePublicationGate()
        val firstRadio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val secondRadio = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        var published = false

        assertFalse(
            gate.publishMutation(firstRadio, currentRadio = { secondRadio }) {
                published = true
            },
        )
        assertFalse(published)
    }
}
