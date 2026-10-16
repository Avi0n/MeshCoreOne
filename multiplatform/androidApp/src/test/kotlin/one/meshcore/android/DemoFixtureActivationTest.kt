package one.meshcore.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoFixtureActivationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchingBetweenActiveModesDoesNotReseedFixtures() = runTest {
        val demo = MutableStateFlow(false)
        val screenshot = MutableStateFlow(false)
        val activations = mutableListOf<Boolean>()
        backgroundScope.launch { demoFixtureActivation(demo, screenshot).collect(activations::add) }
        runCurrent()

        demo.value = true
        runCurrent()
        screenshot.value = true
        runCurrent()
        demo.value = false
        runCurrent()
        screenshot.value = false
        runCurrent()

        assertEquals(listOf(false, true, false), activations)
    }
}
