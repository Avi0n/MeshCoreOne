package one.meshcore.swiftcore

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the jextract-generated JNI bindings compiled into this module and are loadable at the
 * classloader level. Classes are resolved with `initialize = false` so the native `static {}`
 * block that would `System.loadLibrary("MeshCoreBridgeKit")` never runs — loading the real .so
 * needs a device and belongs to meshcore-kt's instrumented tests. This is purely a shape check:
 * it catches the module-name/type-name jextract collision (which silently empties MeshCoreBridge)
 * and a broken binding-compilation wiring.
 */
class GeneratedBindingsSmokeTest {
    private val bindingPackage = "one.meshcore.bridge"
    private val loader = javaClass.classLoader!!

    private fun loadWithoutInit(simpleName: String): Class<*> =
        Class.forName("$bindingPackage.$simpleName", false, loader)

    @Test
    fun `every generated binding class is present on the classpath`() {
        listOf(
            "MeshCoreBridge",
            "BridgeErrorCode",
            "BridgeSendListener",
            "BridgeResultListener",
            "BridgeEventListener",
            "BridgeLogListener",
        ).forEach { name ->
            assertNotNull("Generated binding $bindingPackage.$name is missing", loadWithoutInit(name))
        }
    }

    @Test
    fun `MeshCoreBridge exposes its command surface, not an empty shell`() {
        val bridge = loadWithoutInit("MeshCoreBridge")
        val methodNames = bridge.declaredMethods.map { it.name }.toSet()
        // A representative slice of the ~60 commands; if the jextract module/type-name collision
        // regresses, MeshCoreBridge.java is emitted empty and none of these exist.
        listOf("init", "startSession", "sendMessage", "queryDevice", "receiveBytes", "reportConnected")
            .forEach { command ->
                assertTrue("MeshCoreBridge is missing generated method '$command'", command in methodNames)
            }
    }

    @Test
    fun `listener bindings are interfaces with their callback methods`() {
        val sendListener = loadWithoutInit("BridgeSendListener")
        assertTrue("BridgeSendListener should be an interface", sendListener.isInterface)
        assertTrue(
            "BridgeSendListener should declare sendBytes",
            sendListener.declaredMethods.any { it.name == "sendBytes" },
        )
    }
}
