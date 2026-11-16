@testable import MC1Services
import Testing

@Suite("MECP quantity line")
struct MECPQuantityLineTests {
  @Test
  func `each locale formats the person count with its plural rules`() {
    let lines: [(Int, String, String)] = [
      (1, "en", "1 person"),
      (3, "en", "3 people"),
      (1, "de", "1 Person"),
      (3, "de", "3 Personen"),
      (1, "es", "1 persona"),
      (3, "es", "3 personas"),
      (1, "fr", "1 personne"),
      (3, "fr", "3 personnes"),
      (1, "it", "1 persona"),
      (3, "it", "3 persone"),
      (1, "nl", "1 persoon"),
      (3, "nl", "3 personen"),
      (1, "pt", "1 pessoa"),
      (3, "pt", "3 pessoas"),
      (1, "zh-Hans", "1 人"),
      (3, "zh-Hans", "3 人"),
      (1, "pl", "1 osoba"),
      (2, "pl", "2 osoby"),
      (5, "pl", "5 osób"),
      (21, "pl", "21 osób"),
      (22, "pl", "22 osoby"),
      (1, "ru", "1 человек"),
      (2, "ru", "2 человека"),
      (5, "ru", "5 человек"),
      (21, "ru", "21 человек"),
      (22, "ru", "22 человека"),
      (1, "uk", "1 людина"),
      (2, "uk", "2 людини"),
      (5, "uk", "5 людей"),
      (21, "uk", "21 людина"),
      (22, "uk", "22 людини"),
      (1, "ko", "1 person"),
      (3, "ko", "3 people"),
    ]
    for (count, localization, expected) in lines {
      let line = MECPCatalog.quantityLine(count: count, preferredLocalization: localization, bundle: .main)
      #expect(line == expected)
    }
  }
}
