// AndroidOnly: WP-504 Workload reproducibility assertions, not performance or device acceptance.
package com.meshcoreone.android.benchmark

import java.nio.charset.StandardCharsets
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class WorkloadTest {
    @Test fun fixedCounts() {
        assertEquals(10_000, Workload.messages().size)
        assertEquals(1_000, Workload.markers().size)
    }

    @Test fun stableInputs() {
        assertEquals(Workload.messages(), Workload.messages())
        assertEquals(Workload.markers(), Workload.markers())
        assertEquals(Workload.digest(), Workload.digest())
    }

    @Test fun distinctIdentitiesAndRadioPartition() {
        val ids = Workload.messages().map { it.id } + Workload.markers().map { it.id } +
            listOf(Workload.radioId, Workload.contactId)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun fixedUnixSecondsAndOrder() {
        val messages = Workload.messages()
        assertEquals(1_735_689_600L, messages.first().epochSeconds)
        messages.zipWithNext().forEach { (first, second) ->
            assertEquals(60L, second.epochSeconds - first.epochSeconds)
        }
    }

    @Test fun representativeTextFitsMeshPayload() {
        val texts = Workload.messages().map { it.text }
        assertTrue(texts.all { it.toByteArray(StandardCharsets.UTF_8).size <= 140 })
        assertTrue(texts.any { it.contains('\n') })
        assertTrue(texts.any { it.contains('\u65e5') })
        assertTrue(texts.any { it.contains('\u0645') })
        assertTrue(texts.any { it.contains("fixture.invalid") })
    }

    @Test fun bothMessageDirections() {
        assertEquals(5_000, Workload.messages().count { it.outgoing })
        assertEquals(5_000, Workload.messages().count { !it.outgoing })
    }

    @Test fun validDistinctMarkerGrid() {
        val markers = Workload.markers()
        assertTrue(markers.all { it.latitude in 37.0..37.05 && it.longitude in -122.0..-121.92 })
        assertEquals(markers.size, markers.map { it.latitude to it.longitude }.toSet().size)
    }

    @Test fun digestIsLocaleIndependent() {
        val before = Locale.getDefault()
        val expected = Workload.digest()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals(expected, Workload.digest())
            assertTrue(expected.matches(Regex("[0-9a-f]{64}")))
        } finally {
            Locale.setDefault(before)
        }
    }
}
