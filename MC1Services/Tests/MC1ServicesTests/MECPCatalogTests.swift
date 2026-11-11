import Foundation
@testable import MC1Services
import Testing

@Suite("MECP catalog")
struct MECPCatalogTests {
  private let canonical = "MECP/0/M01 M02 T04 3pax 48.8566,2.3522 @1430 @en"
  private let englishReading = [
    "MAYDAY",
    "Injury",
    "Unconscious person",
    "Flooding",
    "3 people",
    "14:30 UTC",
    "48.8566,2.3522 @en",
  ].joined(separator: "\n")

  @Test
  func `the canonical reading joins severity, phrases, the count, the clock, and the leftover tail`() throws {
    let reading = try #require(MECPCatalog.reading(canonical, bundle: .module, preferredLocalizations: ["en"]))
    #expect(reading == englishReading)
    let display = MECPCatalog.displayText(canonical, bundle: .module, preferredLocalizations: ["en"])
    #expect(display == englishReading.replacingOccurrences(of: "\n", with: " "))
    #expect(!display.contains("3pax"))
    #expect(!display.contains("@1430"))
    #expect(!display.contains("Person count"))
  }

  @Test
  func `one person uses the singular quantity line`() {
    let display = MECPCatalog.displayText("MECP/3/M01 1pax", bundle: .module, preferredLocalizations: ["en"])
    #expect(display.contains("1 person"))
    #expect(!display.contains("1 people"))
  }

  @Test
  func `an R03 eta replaces the bracket slot and leaves the unit word`() {
    #expect(display("MECP/1/R03 15", localization: "en") == "URGENT ETA 15 minutes")
    #expect(display("MECP/1/R03 15", localization: "de") == "DRINGEND Ankunft in 15 Minuten")
    #expect(display("MECP/1/R03 15", localization: "zh-Hans") == "紧急 预计到达 15 分钟")

    let slots = [
      "es": "Llegada est. 15 minutos",
      "fr": "Arrivée dans 15 minutes",
      "it": "Arrivo stimato 15 minuti",
      "nl": "Aankomst over 15 minuten",
      "pl": "Czas przybycia 15 minuty",
      "pt": "Chegada em 15 minutos",
      "ru": "Время прибытия 15 минут",
      "uk": "Час прибуття 15 хвилин",
    ]
    for (localization, phrase) in slots {
      let text = display("MECP/1/R03 15", localization: localization)
      #expect(text.contains(phrase))
      #expect(!text.contains("["))
      #expect(text.filter { $0 == "1" }.count == 1)
    }

    let unset = display("MECP/1/R03 near 15", localization: "en")
    #expect(unset.contains("[minutes]"))
  }

  @Test
  func `H07 replaces the N slot with the person count`() {
    let text = display("MECP/3/H07 4pax", localization: "en")
    #expect(text.contains("Have shelter / space for 4pax"))
    #expect(!text.contains("[N]"))
  }

  @Test
  func `pack choice uses the first preferred localization`() {
    let available = ["en", "de", "zh-Hans", "ko"]
    let simplified = Bundle.preferredLocalizations(from: available, forPreferences: ["zh-Hans"])
    #expect(MECPCatalog.firstPreferredLocalization(simplified) == "zh-Hans")
    let simplifiedText = MECPCatalog.displayText(
      "MECP/0/M01",
      bundle: .module,
      preferredLocalizations: simplified
    )
    #expect(simplifiedText.contains("受伤"))
    #expect(!simplifiedText.contains("Injury"))

    #expect(display("MECP/0/M01", localization: "de").contains("Verletzung"))

    let korean = display("MECP/0/M01 1pax", localization: "ko")
    #expect(korean.contains("Injury"))
    #expect(korean.contains("1 person"))
    let koreanMany = display("MECP/0/M01 3pax", localization: "ko")
    #expect(koreanMany.contains("3 people"))
  }

  @Test
  func `a code missing from English uses the active pack unknown label`() throws {
    let english = try #require(MECPCatalog.languagePack(stem: "en"))
    let unknown = try #require(english.unknownCode)
    #expect(unknown == "Unknown code")
    let englishText = display("MECP/0/Z99", localization: "en")
    #expect(englishText.hasSuffix("Z99: \(unknown)"))

    let german = display("MECP/0/Z99", localization: "de")
    #expect(german.hasSuffix("Unbekannter Code"))
  }

  @Test
  func `a pack with no unknown label uses the literal fallback`() throws {
    let data = Data(#"{"severities":{"0":{"local":"MAYDAY"}},"codes":{},"ui":{}}"#.utf8)
    let pack = try #require(MECPCatalog.LanguagePack(data: data))
    let english = MECPCatalog.LanguagePack(severityLocal: [:], codes: [:], unknownCode: nil)
    let message = try #require(MECPDecode.decode("MECP/0/Z99"))
    #expect(MECPCatalog.phrase(for: "Z99", message: message, pack: pack, english: english) == "Z99: Unknown code")
  }

  @Test
  func `every English code exists in the other packs`() throws {
    let english = try #require(MECPCatalog.languagePack(stem: "en"))
    let others = MECPCatalog.packStems.filter { $0 != "en" }
    for stem in others {
      let pack = try #require(MECPCatalog.languagePack(stem: stem))
      let missing = english.codes.keys.filter { pack.codes[$0] == nil }.sorted()
      #expect(missing.isEmpty)
    }
  }

  @Test
  func `a failed pack load returns the input unchanged`() {
    let input = "MECP/0/M01"
    let display = MECPCatalog.displayText(
      input,
      bundle: .module,
      preferredLocalizations: ["en"],
      resourceBundle: .module
    )
    #expect(display == input)
  }

  @Test
  func `text that does not decode is unchanged`() {
    #expect(MECPCatalog.displayText("hello", bundle: .module, preferredLocalizations: ["en"]) == "hello")
  }

  private func display(_ text: String, localization: String) -> String {
    MECPCatalog.displayText(text, bundle: .module, preferredLocalizations: [localization])
  }
}
