@testable import MC1Services
import Testing

@Suite("RadioPresets repeat mode")
struct RepeatPresetTests {
  /// The firmware (isValidClientRepeatFreq) accepts only these exact frequencies.
  private static let firmwareRepeatFreqsKHz: [String: UInt32] = [
    "repeat-433": 433_000,
    "repeat-869": 869_495,
    "repeat-918": 918_000,
  ]

  @Test
  func `repeat preset frequencies match the firmware's allowed set exactly`() {
    for preset in RadioPresets.repeatPresets {
      let expected = Self.firmwareRepeatFreqsKHz[preset.id]
      #expect(expected != nil, "unexpected repeat preset id \(preset.id)")
      #expect(preset.frequencyKHz == expected)
    }
    #expect(RadioPresets.repeatPresets.count == Self.firmwareRepeatFreqsKHz.count)
  }

  @Test
  func `matchingRepeatPreset resolves by frequency only`() {
    #expect(RadioPresets.matchingRepeatPreset(frequencyKHz: 869_495)?.id == "repeat-869")
    #expect(RadioPresets.matchingRepeatPreset(frequencyKHz: 433_000)?.id == "repeat-433")
    #expect(RadioPresets.matchingRepeatPreset(frequencyKHz: 918_000)?.id == "repeat-918")
  }

  @Test
  func `matchingRepeatPreset returns nil for a non-repeat frequency`() {
    #expect(RadioPresets.matchingRepeatPreset(frequencyKHz: 869_000) == nil)
    #expect(RadioPresets.matchingRepeatPreset(frequencyKHz: 915_000) == nil)
  }

  @Test
  func `nearestRepeatPreset snaps an off-band frequency to the closest allowed one`() {
    #expect(RadioPresets.nearestRepeatPreset(toFrequencyKHz: 869_000)?.id == "repeat-869")
    #expect(RadioPresets.nearestRepeatPreset(toFrequencyKHz: 915_000)?.id == "repeat-918")
    #expect(RadioPresets.nearestRepeatPreset(toFrequencyKHz: 868_000)?.id == "repeat-869")
    #expect(RadioPresets.nearestRepeatPreset(toFrequencyKHz: 500_000)?.id == "repeat-433")
  }

  @Test
  func `nearestRepeatPreset returns an already-valid frequency unchanged`() {
    #expect(RadioPresets.nearestRepeatPreset(toFrequencyKHz: 869_495)?.id == "repeat-869")
    #expect(RadioPresets.nearestRepeatPreset(toFrequencyKHz: 918_000)?.id == "repeat-918")
  }
}
