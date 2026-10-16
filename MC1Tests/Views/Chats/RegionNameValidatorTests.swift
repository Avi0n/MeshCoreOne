@testable import MC1
@testable import MC1Services
import Testing

@Suite("RegionNameValidator")
struct RegionNameValidatorTests {
  // MARK: - Valid Names

  @Test(arguments: [
    "Europe", "UK", "France", "sample-city", "region-1"
  ])
  func `accepts standard region names`(name: String) {
    #expect(RegionNameValidator.isValid(name, existingRegions: []))
  }

  // MARK: - Invalid Names

  @Test
  func `rejects empty name`() {
    #expect(RegionNameValidator.validate("", existingRegions: []) == .empty)
  }

  @Test
  func `rejects whitespace-only name`() {
    #expect(RegionNameValidator.validate("   ", existingRegions: []) == .empty)
  }

  @Test
  func `rejects name with spaces`() {
    #expect(RegionNameValidator.validate("my region", existingRegions: []) == .invalidCharacters)
  }

  @Test
  func `rejects unicode characters`() {
    #expect(RegionNameValidator.validate("Île-de-France", existingRegions: []) == .invalidCharacters)
  }

  @Test(arguments: ["hello!", "foo@bar", "a&b", "test.region", "#Europe", "$secret"])
  func `rejects special characters`(name: String) {
    #expect(RegionNameValidator.validate(name, existingRegions: []) == .invalidCharacters)
  }

  // MARK: - Overlong Names

  @Test
  func `accepts names at the byte cap`() {
    let maxBytes = ProtocolLimits.maxDefaultFloodScopeNameBytes
    let name = String(repeating: "a", count: maxBytes)
    #expect(name.utf8.count == maxBytes)
    #expect(RegionNameValidator.isValid(name, existingRegions: []))
  }

  @Test
  func `rejects names one byte over the cap`() {
    let maxBytes = ProtocolLimits.maxDefaultFloodScopeNameBytes
    let name = String(repeating: "a", count: maxBytes + 1)
    #expect(
      RegionNameValidator.validate(name, existingRegions: [])
        == .tooLong(maxBytes: maxBytes)
    )
  }

  // MARK: - Duplicates

  @Test
  func `rejects duplicate region name`() {
    #expect(RegionNameValidator.validate("Europe", existingRegions: ["Europe"]) == .duplicate)
  }

  @Test
  func `duplicate check is case-sensitive`() {
    #expect(RegionNameValidator.isValid("europe", existingRegions: ["Europe"]))
  }

  // MARK: - isValid convenience

  @Test
  func `isValid returns true for valid name`() {
    #expect(RegionNameValidator.isValid("Europe", existingRegions: []))
  }

  @Test
  func `isValid returns false for invalid name`() {
    #expect(!RegionNameValidator.isValid("", existingRegions: []))
  }
}
