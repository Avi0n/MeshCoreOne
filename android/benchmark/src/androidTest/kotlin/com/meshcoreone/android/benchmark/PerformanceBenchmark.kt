// AndroidOnly: WP-504 Release/R8 startup and real Room/Compose/native-map workloads; no error suppression.
package com.meshcoreone.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PerformanceBenchmark(private val compilation: CompilationMode) {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun prepare() = prepareWorkload(requireApplicationProfile = compilation is CompilationMode.Partial)

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )

    @Test fun timelinePaging() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = 10,
        setupBlock = { pressHome(); openWorkload("timeline") },
        measureBlock = { scrollTimeline() },
    )

    @Test fun nativeMapPan() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = 10,
        setupBlock = { pressHome(); openWorkload("map") },
        measureBlock = { panMap() },
    )

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun compilationModes(): List<Array<CompilationMode>> = listOf(
            arrayOf(CompilationMode.None()),
            arrayOf(CompilationMode.Full()),
            arrayOf(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)),
        )
    }
}
