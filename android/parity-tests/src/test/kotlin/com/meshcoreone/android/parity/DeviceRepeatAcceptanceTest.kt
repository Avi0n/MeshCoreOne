// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/DeviceDTOClientRepeatTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.SelfInfo
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DeviceRepeatAcceptanceTest {
    @Test fun sourcePreRepeatParametersSurviveSelfInfoWithoutMutatingTheOriginal() {
        val original = DeviceDTO(
            radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "TestDevice",
            firmwareVersion = 9u, frequency = 915_000u, bandwidth = 250_000u, spreadingFactor = 8u, codingRate = 5u,
            clientRepeat = true, preRepeatFrequency = 915_000u, preRepeatBandwidth = 250_000u,
            preRepeatSpreadingFactor = 8u, preRepeatCodingRate = 5u,
        )
        val info = SelfInfo(1u, 20, 30, Bytes(ByteArray(32) { 0xAB.toByte() }), 37.7749, -122.4194,
            2u, 1u, 0u, 0u, 2u, false, 906.875, 250.0, 11u, 8u, "UpdatedNode")
        val updated = original.updating(info)
        assertTrue(updated.clientRepeat)
        assertEquals(915_000u, updated.preRepeatFrequency)
        assertEquals(250_000u, updated.preRepeatBandwidth)
        assertEquals(8.toUByte(), updated.preRepeatSpreadingFactor)
        assertEquals(5.toUByte(), updated.preRepeatCodingRate)
        assertTrue(updated.hasPreRepeatSettings)
        assertEquals(906_875u, updated.frequency)
        assertEquals(250_000u, updated.bandwidth)
        assertEquals(11.toUByte(), updated.spreadingFactor)
        assertEquals(8.toUByte(), updated.codingRate)
        assertEquals("UpdatedNode", updated.nodeName)
        assertEquals(915_000u, original.frequency)
        assertEquals(8.toUByte(), original.spreadingFactor)
        assertEquals("TestDevice", original.nodeName)
    }
}
