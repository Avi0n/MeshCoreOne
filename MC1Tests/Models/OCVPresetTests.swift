@testable import MC1Services
import Testing

@Suite("OCVPreset Tests")
struct OCVPresetTests {
  @Test(arguments: OCVPreset.allCases.filter { $0 != .custom })
  func `All presets have exactly 11 values`(preset: OCVPreset) {
    #expect(preset.ocvArray.count == 11, "Preset \(preset) should have 11 values")
  }

  @Test(arguments: OCVPreset.allCases.filter { $0 != .custom })
  func `All preset arrays are descending`(preset: OCVPreset) {
    let array = preset.ocvArray
    for i in 0..<(array.count - 1) {
      #expect(array[i] > array[i + 1], "Preset \(preset) should be descending at index \(i)")
    }
  }

  @Test(arguments: OCVPreset.allCases.filter { $0 != .custom })
  func `All presets fit within UI validation range`(preset: OCVPreset) {
    for value in preset.ocvArray {
      #expect(
        OCVPreset.validMillivoltRange.contains(value),
        "Preset \(preset) has value \(value) outside \(OCVPreset.validMillivoltRange)"
      )
    }
  }

  @Test(arguments: OCVPreset.allCases)
  func `All presets have display names`(preset: OCVPreset) {
    #expect(!preset.displayName.isEmpty, "Preset \(preset) should have a display name")
  }

  @Test
  func `Selectable presets excludes custom`() {
    #expect(!OCVPreset.selectablePresets.contains(.custom))
    #expect(OCVPreset.selectablePresets.count == OCVPreset.allCases.count - 1)
  }

  @Test
  func `Li-Ion preset has expected values`() {
    let expected = [4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100]
    #expect(OCVPreset.liIon.ocvArray == expected)
  }

  @Test
  func `WisMesh Tag preset has expected values`() {
    let expected = [4160, 4020, 3940, 3870, 3810, 3760, 3740, 3720, 3680, 3620, 2990]
    #expect(OCVPreset.wisMeshTag.ocvArray == expected)
  }

  @Test
  func `LilyGo T-Beam 1W preset has expected values`() {
    let expected = [7950, 7850, 7750, 7580, 7440, 7310, 7150, 7005, 6860, 6685, 6000]
    #expect(OCVPreset.lilyGoTBeam1W.ocvArray == expected)
  }

  @Test
  func `ThinkNode M6 preset has expected values`() {
    let expected = [4080, 3990, 3935, 3880, 3825, 3770, 3715, 3660, 3605, 3550, 3450]
    #expect(OCVPreset.thinkNodeM6.ocvArray == expected)
  }

  // MARK: - Category Tests

  @Test
  func `Battery chemistry presets include only chemistry types`() {
    let presets = OCVPreset.batteryChemistryPresets

    #expect(presets.contains(.liIon))
    #expect(presets.contains(.liFePO4))
    #expect(presets.contains(.leadAcid))
    #expect(presets.contains(.alkaline))
    #expect(presets.contains(.niMH))
    #expect(presets.contains(.lto))
    #expect(presets.count == 6)
  }

  @Test
  func `Battery chemistry presets exclude device-specific presets`() {
    let presets = OCVPreset.batteryChemistryPresets

    #expect(!presets.contains(.trackerT1000E))
    #expect(!presets.contains(.heltecPocket5000))
    #expect(!presets.contains(.custom))
  }

  @Test
  func `Li-Ion is battery chemistry category`() {
    #expect(OCVPreset.liIon.category == .batteryChemistry)
  }

  @Test
  func `Tracker T1000-E is device specific category`() {
    #expect(OCVPreset.trackerT1000E.category == .deviceSpecific)
  }

  @Test
  func `Custom is device specific category`() {
    #expect(OCVPreset.custom.category == .deviceSpecific)
  }

  // MARK: - Manufacturer Matching Tests

  @Test
  func `Seeed Tracker T1000-e maps to trackerT1000E preset`() {
    #expect(OCVPreset.preset(forManufacturer: "Seeed Tracker T1000-e") == .trackerT1000E)
  }

  @Test
  func `Seeed Wio Tracker L1 maps to seeedWioTracker preset`() {
    #expect(OCVPreset.preset(forManufacturer: "Seeed Wio Tracker L1") == .seeedWioTracker)
  }

  @Test
  func `Seeed SenseCap Solar maps to seeedSolarNode preset`() {
    #expect(OCVPreset.preset(forManufacturer: "Seeed SenseCap Solar") == .seeedSolarNode)
  }

  @Test
  func `RAK WisMesh Tag maps to wisMeshTag preset`() {
    #expect(OCVPreset.preset(forManufacturer: "RAK WisMesh Tag") == .wisMeshTag)
  }

  @Test
  func `LilyGo T-Beam 1W maps to lilyGoTBeam1W preset`() {
    #expect(OCVPreset.preset(forManufacturer: "LilyGo T-Beam 1W") == .lilyGoTBeam1W)
  }

  @Test
  func `Elecrow ThinkNode M6 maps to thinkNodeM6 preset`() {
    #expect(OCVPreset.preset(forManufacturer: "Elecrow ThinkNode M6") == .thinkNodeM6)
  }

  @Test
  func `Unknown manufacturer returns nil`() {
    #expect(OCVPreset.preset(forManufacturer: "Generic ESP32") == nil)
    #expect(OCVPreset.preset(forManufacturer: "Heltec MeshPocket") == nil)
    #expect(OCVPreset.preset(forManufacturer: "") == nil)
  }

  @Test
  func `Manufacturer matching is case-sensitive`() {
    #expect(OCVPreset.preset(forManufacturer: "seeed tracker t1000-e") == nil)
    #expect(OCVPreset.preset(forManufacturer: "SEEED TRACKER T1000-E") == nil)
  }
}
