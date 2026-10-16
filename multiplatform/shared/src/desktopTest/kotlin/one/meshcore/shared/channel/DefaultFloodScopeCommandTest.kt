package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.meshcore.DefaultFloodScope
import one.meshcore.shared.meshcore.MeshSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A region scope is sent as a name. The 16-byte key the firmware stores is derived from that name
 * below the bridge, so nothing above it hashes a region — these tests hold that line.
 */
class DefaultFloodScopeCommandTest {

    @Test
    fun aRegionScopeTravelsAsItsNameAndCarriesNoKey() = runTest {
        val session = RecordingFloodScopeSession(readBack = "Europe")

        assertEquals("Europe", setDefaultFloodScopeVerified(session, "  Europe  "))

        assertEquals("Europe", session.name)
        assertEquals("region", session.scopeType)
        assertEquals("Europe", session.scopeText, "the trimmed name is what the key is derived from")
        assertTrue(session.scopeKey!!.isEmpty(), "no scope key is derived above the bridge")
    }

    @Test
    fun clearingSendsTheDisabledScope() = runTest {
        val session = RecordingFloodScopeSession(readBack = null)

        assertNull(setDefaultFloodScopeVerified(session, "   "))

        assertEquals("disabled", session.scopeType)
        assertEquals("", session.name)
    }

    @Test
    fun aDisagreeingReadBackFailsVerification() = runTest {
        val session = RecordingFloodScopeSession(readBack = "Elsewhere")

        val error = assertFailsWith<DefaultFloodScopeVerificationFailed> {
            setDefaultFloodScopeVerified(session, "Europe")
        }
        assertEquals("Europe", error.expected)
        assertEquals("Elsewhere", error.actual)
    }

    private class RecordingFloodScopeSession(
        private val readBack: String?,
        private val delegate: FakeMeshSession = FakeMeshSession(),
    ) : MeshSession by delegate {
        var name: String? = null
        var scopeType: String? = null
        var scopeText: String? = null
        var scopeKey: ByteArray? = null

        override suspend fun setDefaultFloodScope(
            name: String,
            scopeType: String,
            scopeText: String,
            scopeKey: ByteArray,
        ) {
            this.name = name
            this.scopeType = scopeType
            this.scopeText = scopeText
            this.scopeKey = scopeKey
        }

        override suspend fun getDefaultFloodScope(): DefaultFloodScope? =
            readBack?.let { DefaultFloodScope(name = it, scopeKey = ByteArray(0)) }
    }
}
