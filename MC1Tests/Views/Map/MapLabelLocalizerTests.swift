import Foundation
import MapLibre
@testable import MC1
import Testing

@Suite("Map label localization")
@MainActor
struct MapLabelLocalizerTests {
  @Test(arguments: ["liberty", "dark"], ["de", "fr", "zh-Hans"])
  func `recorded basemap name layers use the app language`(styleName: String, languageCode: String) throws {
    let fixture = try makeFixture(styleName: styleName)
    let originals = fixture.layers.map(\.text)
    let nameFields = languageCode == "de" ? ["name:de", "name_de", "name"] : ["name:\(languageCode)", "name"]
    let expected = try normalizedText(["coalesce"] + nameFields.map { ["get", $0] })

    MapLabelLocalizer.localize(fixture.layers, languageCode: languageCode)

    for (index, layer) in fixture.layers.enumerated() {
      if fixture.nameLayerIDs.contains(layer.identifier) {
        let text = try #require(layer.text)
        #expect(text.isEqual(expected), "\(styleName)/\(layer.identifier) should use \(languageCode)")
      } else {
        #expect(layer.text == originals[index], "\(layer.identifier) must preserve its reference or icon")
      }
    }
  }

  @Test(arguments: ["liberty", "dark"])
  func `English leaves every recorded text expression unchanged`(styleName: String) throws {
    let fixture = try makeFixture(styleName: styleName)
    let originals = fixture.layers.map(\.text)

    MapLabelLocalizer.localize(fixture.layers, languageCode: "en")

    #expect(fixture.layers.map(\.text) == originals)
  }

  @Test
  func `radio names and unrecognized basemap expressions are preserved`() {
    let basemapSource = MLNShapeSource(identifier: "openmaptiles", features: [], options: nil)
    let contactSource = MLNShapeSource(identifier: MapSourceID.points, features: [], options: nil)
    let contact = MLNSymbolStyleLayer(identifier: MapLayerID.nameLabels, source: contactSource)
    contact.text = NSExpression(mglJSONObject: ["coalesce", ["get", "name_en"], ["get", "name"]])
    let otherName = MLNSymbolStyleLayer(identifier: "other-name", source: basemapSource)
    otherName.text = NSExpression(forKeyPath: "name")
    let layers = [contact, otherName]
    let originals = layers.map(\.text)

    MapLabelLocalizer.localize(layers, languageCode: "de")

    #expect(layers.map(\.text) == originals)
  }

  @Test
  func `direct English fallback is localized with the local name last`() throws {
    let source = MLNShapeSource(identifier: "openmaptiles", features: [], options: nil)
    let layer = MLNSymbolStyleLayer(identifier: "older-name", source: source)
    layer.text = NSExpression(mglJSONObject: ["coalesce", ["get", "name_en"], ["get", "name"]])
    let expected = try normalizedText(["coalesce", ["get", "name:fr"], ["get", "name"]])

    MapLabelLocalizer.localize([layer], languageCode: "fr")

    #expect(layer.text == expected)
  }

  private func makeFixture(styleName: String) throws -> (layers: [MLNSymbolStyleLayer], nameLayerIDs: Set<String>) {
    let url = try #require(Bundle(for: FixtureBundle.self).url(forResource: "openfreemap-labels", withExtension: "json"))
    let data = try Data(contentsOf: url)
    let document = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    let styles = try #require(document["styles"] as? [String: Any])
    let style = try #require(styles[styleName] as? [String: Any])
    let nameLayerIDs = try #require(style["nameLayerIDs"] as? [String])
    let records = try #require(style["layers"] as? [[String: Any]])
    let sourceURL = try #require(URL(string: "https://tiles.openfreemap.org/planet"))
    let source = MLNVectorTileSource(identifier: "openmaptiles", configurationURL: sourceURL)
    let layers = try records.map { record in
      let identifier = try #require(record["id"] as? String)
      let layer = MLNSymbolStyleLayer(identifier: identifier, source: source)
      layer.sourceLayerIdentifier = record["source-layer"] as? String
      if let text = record["text-field"], !(text is NSNull) {
        layer.text = NSExpression(mglJSONObject: text)
      }
      return layer
    }
    return (layers, Set(nameLayerIDs))
  }

  private func normalizedText(_ json: [Any]) throws -> NSExpression {
    let source = MLNShapeSource(identifier: "expected", features: [], options: nil)
    let layer = MLNSymbolStyleLayer(identifier: "expected", source: source)
    layer.text = NSExpression(mglJSONObject: json)
    return try #require(layer.text)
  }

  private final class FixtureBundle {}
}
