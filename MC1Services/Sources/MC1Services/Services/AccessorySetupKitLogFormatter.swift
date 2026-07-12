import Foundation

enum AccessorySetupKitLogFormatter {
  private static let criteriaPreviewCount = 4

  static func criteriaSummary(_ criteria: [AccessorySetupKitDiscoveryCriterion]) -> String {
    let preview = criteria.prefix(criteriaPreviewCount).map(\.bluetoothNameSubstring)
    let remainderCount = criteria.count - preview.count
    let previewText = preview.joined(separator: ", ")

    if remainderCount > 0 {
      return "\(criteria.count) prefixes [\(previewText), +\(remainderCount) more]"
    }

    return "\(criteria.count) prefixes [\(previewText)]"
  }

  static func selectionMessage(
    accessoryName: String,
    bluetoothID: UUID?,
    elapsed: TimeInterval?
  ) -> String {
    let identifier = bluetoothID?.uuidString ?? "none"
    return "[ASK] Selected accessory '\(accessoryName)' (id: \(identifier)) after \(durationSummary(elapsed))"
  }

  static func dismissalMessage(
    outcome: String,
    pairedCount: Int,
    elapsed: TimeInterval?,
    filteredDiscovery: Bool
  ) -> String {
    "[ASK] Picker dismissed after \(durationSummary(elapsed)) (outcome: \(outcome), filteredDiscovery: \(filteredDiscovery), pairedCount: \(pairedCount))"
  }

  private static func durationSummary(_ elapsed: TimeInterval?) -> String {
    guard let elapsed else { return "unknown" }
    return "\(elapsed.formatted(.number.precision(.fractionLength(1))))s"
  }
}
