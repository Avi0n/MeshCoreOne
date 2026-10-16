package one.meshcore.shared.transport

import com.juul.kable.PeripheralBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reconnect must set Kable `autoConnectIf` so Android `connectGatt(autoConnect=true)` waits for the
 * radio. A first-pair connect keeps the 10s GATT ceiling.
 */
@Suppress("MagicNumber") // The retry timing value is the test input.
class KableAutoConnectOptionsTest {
    @Test
    fun reconnectEnablesOsAutoConnect() {
        val builder = peripheralBuilder()
        builder.applyMeshCoreConnectOptions(autoConnect = true)
        assertTrue(builder.autoConnectEnabled())
    }

    @Test
    fun firstConnectDoesNotUseOsAutoConnect() {
        val builder = peripheralBuilder()
        builder.applyMeshCoreConnectOptions(autoConnect = false)
        assertFalse(builder.autoConnectEnabled())
    }

    @Test
    fun requestedMtuIsTheFullAttCeiling() {
        assertEquals(517, REQUESTED_MTU)
    }

    private fun peripheralBuilder(): PeripheralBuilder {
        val ctor = PeripheralBuilder::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private fun PeripheralBuilder.autoConnectEnabled(): Boolean {
        val getter = javaClass.methods.single { it.name.startsWith("getAutoConnectPredicate") }

        @Suppress("UNCHECKED_CAST")
        val predicate = getter.invoke(this) as () -> Boolean
        return predicate()
    }
}
