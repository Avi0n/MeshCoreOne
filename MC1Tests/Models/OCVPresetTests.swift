import Testing
@testable import MC1Services

@Suite("OCVPreset Tests")
struct OCVPresetTests {

    @Test("All presets have exactly 11 values", arguments: OCVPreset.allCases.filter { $0 != .custom })
    func presetsHaveCorrectLength(preset: OCVPreset) {
        #expect(preset.ocvArray.count == 11, "Preset \(preset) should have 11 values")
    }

    @Test("All preset arrays are descending", arguments: OCVPreset.allCases.filter { $0 != .custom })
    func presetsAreDescending(preset: OCVPreset) {
        let array = preset.ocvArray
        for i in 0..<(array.count - 1) {
            #expect(array[i] > array[i + 1], "Preset \(preset) should be descending at index \(i)")
        }
    }

    @Test("All presets have display names", arguments: OCVPreset.allCases)
    func presetsHaveDisplayNames(preset: OCVPreset) {
        #expect(!preset.displayName.isEmpty, "Preset \(preset) should have a display name")
    }

    @Test("Selectable presets excludes custom")
    func selectablePresetsExcludesCustom() {
        #expect(!OCVPreset.selectablePresets.contains(.custom))
        #expect(OCVPreset.selectablePresets.count == OCVPreset.allCases.count - 1)
    }

    @Test("Li-Ion preset has expected values")
    func liIonPresetValues() {
        let expected = [4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100]
        #expect(OCVPreset.liIon.ocvArray == expected)
    }

    @Test("WisMesh Tag preset has expected values")
    func wisMeshTagPresetValues() {
        let expected = [4240, 4112, 4029, 3970, 3906, 3846, 3824, 3802, 3776, 3650, 3072]
        #expect(OCVPreset.wisMeshTag.ocvArray == expected)
    }

    @Test("LilyGo T-Beam 1W preset has expected values")
    func lilyGoTBeam1WPresetValues() {
        let expected = [7950, 7850, 7750, 7580, 7440, 7310, 7150, 7005, 6860, 6685, 6000]
        #expect(OCVPreset.lilyGoTBeam1W.ocvArray == expected)
    }

    @Test("ThinkNode M6 preset has expected values")
    func thinkNodeM6PresetValues() {
        let expected = [4080, 3990, 3935, 3880, 3825, 3770, 3715, 3660, 3605, 3550, 3450]
        #expect(OCVPreset.thinkNodeM6.ocvArray == expected)
    }

    // MARK: - Category Tests

    @Test("Battery chemistry presets include only chemistry types")
    func batteryChemistryPresetsIncludeOnlyChemistryTypes() {
        let presets = OCVPreset.batteryChemistryPresets

        #expect(presets.contains(.liIon))
        #expect(presets.contains(.liFePO4))
        #expect(presets.contains(.leadAcid))
        #expect(presets.contains(.alkaline))
        #expect(presets.contains(.niMH))
        #expect(presets.contains(.lto))
        #expect(presets.count == 6)
    }

    @Test("Battery chemistry presets exclude device-specific presets")
    func batteryChemistryPresetsExcludeDeviceSpecific() {
        let presets = OCVPreset.batteryChemistryPresets

        #expect(!presets.contains(.trackerT1000E))
        #expect(!presets.contains(.heltecPocket5000))
        #expect(!presets.contains(.custom))
    }

    @Test("Li-Ion is battery chemistry category")
    func liIonIsBatteryChemistry() {
        #expect(OCVPreset.liIon.category == .batteryChemistry)
    }

    @Test("Tracker T1000-E is device specific category")
    func trackerIsDeviceSpecific() {
        #expect(OCVPreset.trackerT1000E.category == .deviceSpecific)
    }

    @Test("Custom is device specific category")
    func customIsDeviceSpecific() {
        #expect(OCVPreset.custom.category == .deviceSpecific)
    }

    // MARK: - Manufacturer Matching Tests

    @Test("Seeed Tracker T1000-e maps to trackerT1000E preset")
    func seeedTrackerMapsCorrectly() {
        #expect(OCVPreset.preset(forManufacturer: "Seeed Tracker T1000-e") == .trackerT1000E)
    }

    @Test("Seeed Wio Tracker L1 maps to seeedWioTracker preset")
    func seeedWioTrackerMapsCorrectly() {
        #expect(OCVPreset.preset(forManufacturer: "Seeed Wio Tracker L1") == .seeedWioTracker)
    }

    @Test("Seeed SenseCap Solar maps to seeedSolarNode preset")
    func seeedSenseCapMapsCorrectly() {
        #expect(OCVPreset.preset(forManufacturer: "Seeed SenseCap Solar") == .seeedSolarNode)
    }

    @Test("RAK WisMesh Tag maps to wisMeshTag preset")
    func rakWisMeshTagMapsCorrectly() {
        #expect(OCVPreset.preset(forManufacturer: "RAK WisMesh Tag") == .wisMeshTag)
    }

    @Test("LilyGo T-Beam 1W maps to lilyGoTBeam1W preset")
    func lilyGoTBeam1WMapsCorrectly() {
        #expect(OCVPreset.preset(forManufacturer: "LilyGo T-Beam 1W") == .lilyGoTBeam1W)
    }

    @Test("Elecrow ThinkNode M6 maps to thinkNodeM6 preset")
    func elecrowThinkNodeM6MapsCorrectly() {
        #expect(OCVPreset.preset(forManufacturer: "Elecrow ThinkNode M6") == .thinkNodeM6)
    }

    @Test("Unknown manufacturer returns nil")
    func unknownManufacturerReturnsNil() {
        #expect(OCVPreset.preset(forManufacturer: "Generic ESP32") == nil)
        #expect(OCVPreset.preset(forManufacturer: "Heltec MeshPocket") == nil)
        #expect(OCVPreset.preset(forManufacturer: "") == nil)
    }

    @Test("Manufacturer matching is case-sensitive")
    func manufacturerMatchingIsCaseSensitive() {
        #expect(OCVPreset.preset(forManufacturer: "seeed tracker t1000-e") == nil)
        #expect(OCVPreset.preset(forManufacturer: "SEEED TRACKER T1000-E") == nil)
    }
}
