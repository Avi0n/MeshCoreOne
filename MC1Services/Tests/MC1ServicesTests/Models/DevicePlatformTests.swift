@testable import MC1Services
import Testing

@Suite("DevicePlatform Detection Tests")
struct DevicePlatformTests {
  // MARK: - ESP32 Devices

  @Test
  func `Heltec V2 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec V2") == .esp32)
  }

  @Test
  func `Heltec V3 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec V3") == .esp32)
  }

  @Test
  func `Heltec V4 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec V4") == .esp32)
  }

  @Test(
    .bug("https://github.com/pocketmesh/pocketmesh/issues/0",
         "Was wrongly matched as nRF52 by 'Tracker' substring")
  )
  func `Heltec Tracker detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec Tracker") == .esp32)
  }

  @Test
  func `Heltec E290 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec E290") == .esp32)
  }

  @Test
  func `Heltec E213 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec E213") == .esp32)
  }

  @Test
  func `Heltec T190 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec T190") == .esp32)
  }

  @Test
  func `Heltec CT62 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Heltec CT62") == .esp32)
  }

  @Test
  func `T-Beam detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "T-Beam") == .esp32)
  }

  @Test
  func `T-Deck detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "T-Deck") == .esp32)
  }

  @Test
  func `T-LoRa detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "T-LoRa") == .esp32)
  }

  @Test
  func `TLora detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "TLora") == .esp32)
  }

  @Test(
    .bug("https://github.com/pocketmesh/pocketmesh/issues/0",
         "Was wrongly matched as nRF52 by 'Seeed' vendor prefix")
  )
  func `Xiao S3 WIO detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Xiao S3 WIO") == .esp32)
  }

  @Test(
    .bug("https://github.com/pocketmesh/pocketmesh/issues/0",
         "Was wrongly matched as nRF52 by 'Seeed' vendor prefix")
  )
  func `Xiao C3 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Xiao C3") == .esp32)
  }

  @Test(
    .bug("https://github.com/pocketmesh/pocketmesh/issues/0",
         "Was wrongly matched as nRF52 by 'Seeed' vendor prefix")
  )
  func `Xiao C6 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Xiao C6") == .esp32)
  }

  @Test(
    .bug("https://github.com/pocketmesh/pocketmesh/issues/0",
         "Was wrongly matched as nRF52 by 'RAK' vendor prefix")
  )
  func `RAK 3112 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "RAK 3112") == .esp32)
  }

  @Test
  func `Station G2 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Station G2") == .esp32)
  }

  @Test
  func `Meshadventurer detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Meshadventurer") == .esp32)
  }

  @Test
  func `Generic ESP32 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "Generic ESP32") == .esp32)
  }

  @Test(
    .bug("https://github.com/pocketmesh/pocketmesh/issues/0",
         "Was wrongly matched as nRF52 by 'Seeed' or other vendor prefix")
  )
  func `ThinkNode M2 detected as ESP32`() {
    #expect(DevicePlatform.detect(from: "ThinkNode M2") == .esp32)
  }

  // MARK: - nRF52 Devices

  @Test
  func `MeshPocket detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "MeshPocket") == .nrf52)
  }

  @Test
  func `Mesh Pocket (with space) detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Mesh Pocket") == .nrf52)
  }

  @Test
  func `T114 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "T114") == .nrf52)
  }

  @Test
  func `Mesh Solar detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Mesh Solar") == .nrf52)
  }

  @Test
  func `Xiao-nrf52 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Xiao-nrf52") == .nrf52)
  }

  @Test
  func `Xiao_nrf52 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Xiao_nrf52") == .nrf52)
  }

  @Test
  func `WM1110 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "WM1110") == .nrf52)
  }

  @Test
  func `Wio Tracker detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Wio Tracker") == .nrf52)
  }

  @Test
  func `T1000-E detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "T1000-E") == .nrf52)
  }

  @Test
  func `SenseCap Solar detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "SenseCap Solar") == .nrf52)
  }

  @Test
  func `WisMesh Tag detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "WisMesh Tag") == .nrf52)
  }

  @Test
  func `RAK 4631 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "RAK 4631") == .nrf52)
  }

  @Test
  func `RAK 3401 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "RAK 3401") == .nrf52)
  }

  @Test
  func `T-Echo detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "T-Echo") == .nrf52)
  }

  @Test
  func `ThinkNode-M1 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "ThinkNode-M1") == .nrf52)
  }

  @Test
  func `ThinkNode M3 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "ThinkNode M3") == .nrf52)
  }

  @Test
  func `ThinkNode-M6 detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "ThinkNode-M6") == .nrf52)
  }

  @Test
  func `Ikoka detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Ikoka") == .nrf52)
  }

  @Test
  func `ProMicro detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "ProMicro") == .nrf52)
  }

  @Test
  func `Minewsemi detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Minewsemi") == .nrf52)
  }

  @Test
  func `Meshtiny detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Meshtiny") == .nrf52)
  }

  @Test
  func `Keepteen detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Keepteen") == .nrf52)
  }

  @Test
  func `Nano G2 Ultra detected as nRF52`() {
    #expect(DevicePlatform.detect(from: "Nano G2 Ultra") == .nrf52)
  }

  // MARK: - Regression: Vendor prefix no longer causes wrong match

  @Test(
    .bug("https://github.com/pocketmesh/pocketmesh/issues/0",
         "Old code matched 'Heltec' prefix as ESP32, but Heltec ships nRF52 devices too")
  )
  func `Bare 'Heltec' vendor name is unknown (not assumed ESP32)`() {
    #expect(DevicePlatform.detect(from: "Heltec") == .unknown)
  }

  // MARK: - Edge Cases

  @Test
  func `Empty model string returns unknown`() {
    #expect(DevicePlatform.detect(from: "") == .unknown)
  }

  @Test
  func `Unrecognized device returns unknown`() {
    #expect(DevicePlatform.detect(from: "SomeNewDevice XYZ") == .unknown)
  }

  // MARK: - Pacing Values

  @Test
  func `ESP32 pacing is 60ms`() {
    #expect(DevicePlatform.esp32.recommendedWritePacing == 0.060)
  }

  @Test
  func `nRF52 pacing is 25ms`() {
    #expect(DevicePlatform.nrf52.recommendedWritePacing == 0.025)
  }

  @Test
  func `Unknown pacing is 60ms (conservative default)`() {
    #expect(DevicePlatform.unknown.recommendedWritePacing == 0.060)
  }
}
