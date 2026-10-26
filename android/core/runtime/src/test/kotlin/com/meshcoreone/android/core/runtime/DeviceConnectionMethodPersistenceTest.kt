// PortedFrom: MC1Services/Tests/MC1ServicesTests/DevicePublicKeyDeduplicationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DeviceIdentity
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import java.util.UUID
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConnectionMethodPersistenceTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("DevicePublicKeyDeduplicationTests",
            "createDevice persists the Bluetooth method supplied by the BLE connect path") {
            withFixture {
                val endpoint = target(UUID.fromString("10000000-0000-4000-8000-000000000001"))

                connect(endpoint)

                val saved = devices.rows.values.single()
                assertEquals(endpoint.deviceId, saved.id)
                assertEquals(RadioId(DeviceIdentity.deriveUUID(publicKey)), saved.radioId)
                assertNotEquals(endpoint.deviceId, saved.radioId.value)
                assertEquals(listOf<ConnectionMethod>(ConnectionMethod.Bluetooth(endpoint.deviceId)), saved.connectionMethods)
                assertEquals(publicKey, saved.publicKey)
                assertEquals(saved, manager.connectedDevice)
                assertEquals(saved, services.single().inputs.device)
                assertEquals(saved.radioId, services.single().token.radioId)
                assertEquals(1, devices.calls.count { it == "save" })
                assertEquals(endpoint.deviceId, last.read().deviceId)
                assertEquals(saved.radioId, last.read().radioId)
                assertEquals(endpoint.deviceId, last.read().bondDeviceId)
            }
        },
        original("DevicePublicKeyDeduplicationTests",
            "createDevice merges a Bluetooth method with an existing WiFi method") {
            withFixture {
                val wifi = ConnectionTarget.WiFi("10.0.0.2", 5000u)
                connect(wifi)
                val wifiDevice = devices.rows.values.single()
                val wifiMethod = ConnectionMethod.WiFi(wifi.host, wifi.port)
                assertEquals(listOf<ConnectionMethod>(wifiMethod), wifiDevice.connectionMethods)
                assertEquals(wifiDevice, services.single().inputs.device)
                assertTrue(manager.disconnect().isComplete)
                val endpoint = target(UUID.fromString("10000000-0000-4000-8000-000000000002"))

                connect(endpoint)

                val saved = devices.rows.values.single()
                assertEquals(endpoint.deviceId, saved.id)
                assertEquals(wifiDevice.radioId, saved.radioId)
                assertNotEquals(endpoint.deviceId, saved.radioId.value)
                assertEquals(listOf(wifiMethod, ConnectionMethod.Bluetooth(endpoint.deviceId)), saved.connectionMethods)
                assertEquals(publicKey, saved.publicKey)
                assertEquals(saved, manager.connectedDevice)
                assertEquals(saved, services.last().inputs.device)
                assertEquals(wifiDevice.radioId, services.last().token.radioId)
                assertNull(devices.rows[wifiDevice.id])
                assertEquals(2, devices.calls.count { it == "save" })
                assertEquals(1, devices.calls.count { it == "delete" })
                assertEquals(endpoint.deviceId, last.read().deviceId)
                assertEquals(wifiDevice.radioId, last.read().radioId)
            }
        },
        original("DevicePublicKeyDeduplicationTests",
            "createDevice replaces a stale Bluetooth peripheral UUID instead of accumulating") {
            withFixture {
                val staleId = UUID.fromString("10000000-0000-4000-8000-000000000003")
                val stableRadio = RadioId(UUID.fromString("20000000-0000-4000-8000-000000000001"))
                val wifi = ConnectionMethod.WiFi("10.0.0.2", 5000u, "Saved WiFi")
                val prior = DeviceDTO(
                    id = staleId, radioId = stableRadio, publicKey = publicKey, nodeName = "Old",
                    connectionMethods = listOf(wifi, ConnectionMethod.Bluetooth(staleId, "Old")).snapshot(),
                    lastContactSync = 0xf0000000u, knownRegions = listOf("regionA", "regionB").snapshot(),
                    appliedRadioPresetID = "br", ocvPreset = "custom", customOCVArrayString = "[4200,3300]",
                )
                devices.rows[prior.id] = prior
                val endpoint = target(UUID.fromString("10000000-0000-4000-8000-000000000004"))

                connect(endpoint)

                val saved = devices.rows.values.single()
                assertEquals(endpoint.deviceId, saved.id)
                assertEquals(stableRadio, saved.radioId)
                assertNotEquals(endpoint.deviceId, saved.radioId.value)
                assertEquals(listOf(ConnectionMethod.Bluetooth(endpoint.deviceId)),
                    saved.connectionMethods.filterIsInstance<ConnectionMethod.Bluetooth>())
                assertEquals(listOf(wifi), saved.connectionMethods.filterIsInstance<ConnectionMethod.WiFi>())
                assertEquals(2, saved.connectionMethods.size)
                assertEquals(publicKey, saved.publicKey)
                assertEquals(prior.lastContactSync, saved.lastContactSync)
                assertEquals(prior.knownRegions, saved.knownRegions)
                assertEquals(prior.appliedRadioPresetID, saved.appliedRadioPresetID)
                assertEquals(prior.ocvPreset, saved.ocvPreset)
                assertEquals(prior.customOCVArrayString, saved.customOCVArrayString)
                assertEquals(saved, manager.connectedDevice)
                assertEquals(saved, services.single().inputs.device)
                assertEquals(stableRadio, services.single().token.radioId)
                assertNull(devices.rows[staleId])
                assertEquals(1, devices.calls.count { it == "save" })
                assertEquals(1, devices.calls.count { it == "delete" })
                assertTrue(devices.calls.indexOf("save") < devices.calls.indexOf("delete"))
                assertEquals(endpoint.deviceId, last.read().deviceId)
                assertEquals(stableRadio, last.read().radioId)
            }
        },
    )
}
