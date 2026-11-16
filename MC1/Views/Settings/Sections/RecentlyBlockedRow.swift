import MC1Services
import SwiftUI

struct RecentlyBlockedRow: View {
  let entry: RecentlyBlockedMessageDTO

  var body: some View {
    VStack(alignment: .leading, spacing: Metrics.lineSpacing) {
      HStack(alignment: .firstTextBaseline, spacing: Metrics.titleSpacing) {
        if let sender = entry.sender {
          Text(Self.highlighted(sender, matches: entry.match.senderHighlights, base: .primary))
            .font(.headline)
            .lineLimit(1)
        }
        Spacer(minLength: Metrics.titleSpacing)
        ConversationTimestamp(date: entry.arrivedAt)
      }
      Text(Self.highlighted(entry.body, matches: entry.match.bodyHighlights, base: .secondary))
        .font(.subheadline)
      Text(Self.caption(
        conversationName: entry.conversationName,
        phrase: entry.match.rule.phrase,
        summary: entry.match.rule.summary
      ))
      .font(.footnote)
      .foregroundStyle(.secondary)
    }
    .fixedSize(horizontal: false, vertical: true)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(Self.accessibilityLabel(for: entry))
  }

  static func caption(conversationName: String?, phrase: String, summary: String) -> String {
    if let conversationName {
      return L10n.Settings.TextRules.Archive.caption(conversationName, phrase, summary)
    }
    return L10n.Settings.TextRules.Archive.captionWithoutChat(phrase, summary)
  }

  static func accessibilityLabel(for entry: RecentlyBlockedMessageDTO) -> String {
    var parts: [String] = []
    if let sender = entry.sender {
      parts.append(labeled(L10n.Settings.TextRules.Field.sender, sender))
    }
    parts.append(labeled(L10n.Settings.TextRules.Field.message, entry.body))
    let rule = L10n.Settings.TextRules.ruleSummary(entry.match.rule.phrase, entry.match.rule.summary)
    parts.append(labeled(L10n.Settings.TextRules.Archive.rule, rule))
    if let conversationName = entry.conversationName {
      parts.append(labeled(L10n.Settings.TextRules.Archive.chat, conversationName))
    }
    let time = entry.arrivedAt.formatted(.dateTime.month(.abbreviated).day().hour().minute())
    parts.append(labeled(L10n.Settings.TextRules.Archive.time, time))
    return parts.formatted(.list(type: .and))
  }

  static func highlighted(
    _ text: String,
    matches: [TextRulesPreference.RuleMatch.Highlight],
    base: Color
  ) -> AttributedString {
    var attributed = AttributedString(text)
    attributed.foregroundColor = base
    for match in matches {
      guard let range = match.range(in: text),
            let start = AttributedString.Index(range.lowerBound, within: attributed),
            let end = AttributedString.Index(range.upperBound, within: attributed) else { continue }
      attributed[start..<end].backgroundColor = Metrics.matchHighlight
      attributed[start..<end].foregroundColor = .primary
    }
    return attributed
  }

  private static func labeled(_ label: String, _ value: String) -> String {
    L10n.Settings.TextRules.Archive.labelPrefix(label) + value
  }

  private enum Metrics {
    static let lineSpacing: CGFloat = 4
    static let titleSpacing: CGFloat = 8
    static let matchHighlightOpacity = 0.4
    static var matchHighlight: Color {
      Color.yellow.opacity(matchHighlightOpacity)
    }
  }
}
