import MapLibre

@MainActor
enum MapLabelLocalizer {
  static let defaultLanguageCode = "en"

  private static let sourceIdentifier = "openmaptiles"
  private static let germanLanguageCode = "de"
  private static let localNameField = "name"
  private static let englishNameField = "name_en"
  private static let legacyGermanNameField = "name_de"

  private static let originalNameExpressions: [NSExpression] = {
    let fallback: [Any] = ["coalesce", ["get", englishNameField], ["get", localNameField]]
    let bilingual: [[Any]] = [" ", "\n"].map { separator in
      [
        "case", ["has", "name:nonlatin"],
        ["concat", ["get", "name:latin"], separator, ["get", "name:nonlatin"]],
        fallback
      ]
    }
    let source = MLNShapeSource(identifier: sourceIdentifier, features: [], options: nil)
    let layer = MLNSymbolStyleLayer(identifier: sourceIdentifier, source: source)
    return ([fallback] + bilingual).compactMap { json in
      layer.text = NSExpression(mglJSONObject: json)
      return layer.text
    }
  }()

  static func localize(_ layers: [MLNStyleLayer], languageCode: String) {
    guard languageCode != defaultLanguageCode else { return }

    var fields = ["name:\(languageCode)"]
    if languageCode == germanLanguageCode {
      fields.append(legacyGermanNameField)
    }
    fields.append(localNameField)
    let expression = NSExpression(mglJSONObject: ["coalesce"] + fields.map { ["get", $0] })

    for case let layer as MLNSymbolStyleLayer in layers where layer.sourceIdentifier == sourceIdentifier {
      guard let text = layer.text,
            originalNameExpressions.contains(where: { $0.isEqual(text) }) else { continue }
      layer.text = expression
    }
  }
}
