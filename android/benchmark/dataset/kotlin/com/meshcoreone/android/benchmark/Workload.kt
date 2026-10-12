// AndroidOnly: WP-504 Synthetic, versioned workload inputs; never measured results or user data.
package com.meshcoreone.android.benchmark

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class WorkloadMessage(val id: UUID, val epochSeconds: Long, val text: String, val outgoing: Boolean)
data class WorkloadMarker(val id: UUID, val latitude: Double, val longitude: Double)

object Workload {
    const val VERSION = "wp504-v1"
    const val MESSAGE_COUNT = 10_000
    const val MARKER_COUNT = 1_000
    const val EPOCH_SECONDS = 1_735_689_600L
    val radioId = UUID(0x504L, 1L)
    val contactId = UUID(0x504L, 2L)

    fun messages(): List<WorkloadMessage> = List(MESSAGE_COUNT) { index ->
        val body = when (index % 6) {
            0 -> "Cached mesh message with a stable identifier and timestamp."
            1 -> "A".repeat(128)
            2 -> "\u65e5\u672c\u8a9e \u4e2d\u6587 \ud55c\uad6d\uc5b4"
            3 -> "\u0645\u0631\u062d\u0628\u0627 \u05e9\u05dc\u05d5\u05dd"
            4 -> "Line one\nLine two\nLine three"
            else -> "https://fixture.invalid/node @Node_0042 #mesh"
        }
        WorkloadMessage(UUID(0x50401L, index.toLong() + 1), EPOCH_SECONDS + index * 60L, "$index $body", index % 2 == 0)
    }

    fun markers(): List<WorkloadMarker> = List(MARKER_COUNT) { index ->
        WorkloadMarker(UUID(0x50402L, index.toLong() + 1), 37.0 + (index / 40) * 0.002, -122.0 + (index % 40) * 0.002)
    }

    fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun line(value: String) = digest.update((value + "\n").toByteArray(StandardCharsets.UTF_8))
        line(VERSION)
        messages().forEach { line("${it.id}|${it.epochSeconds}|${it.outgoing}|${it.text}") }
        markers().forEach { line("${it.id}|${it.latitude}|${it.longitude}") }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
