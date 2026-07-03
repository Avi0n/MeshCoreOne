import MC1Services
import SwiftUI

/// SF Symbol shown beside the send time when the sender's clock was invalid and the
/// app substituted a corrected value, signalling that the displayed time was adjusted.
private let correctedClockBadgeSymbol = "clock.badge.exclamationmark"

/// Renders the in-bubble footer from a `MessageFooter`: send time, then the
/// incoming network slots (hop / path / region) or the outgoing status slots
/// (repeat count + delivery-status icon). Segments are joined with " • "
/// separators at standard dynamic type sizes and stacked vertically at
/// accessibility sizes. Each segment carries its own `.accessibilityLabel(...)`;
/// the container uses `.accessibilityElement(children: .combine)` so VoiceOver
/// surfaces the row as a single rotor stop.
struct BubbleFooterRow: View {
  let footer: MessageFooter
  let dynamicTypeSize: DynamicTypeSize
  /// Color for the send-time text. Varies by bubble: `.secondary` on the gray
  /// incoming bubble, a translucent outgoing-text color on the accent-colored
  /// outgoing bubble where `.secondary` would wash out. Hop/path/region rows
  /// stay `.secondary` because they only ever appear on incoming bubbles.
  var timeColor: Color = .secondary
  /// Retry callback for the failed-status icon; only outgoing bubbles pass one.
  var onRetry: (() -> Void)?

  var body: some View {
    let segments = footerSegments
    Group {
      if dynamicTypeSize.isAccessibilitySize {
        VStack(alignment: .leading, spacing: 2) {
          ForEach(segments.indices, id: \.self) { segments[$0] }
        }
      } else {
        HStack(spacing: 4) {
          ForEach(segments.indices, id: \.self) { index in
            segments[index]
          }
        }
      }
    }
    .accessibilityElement(children: .combine)
  }

  private var footerSegments: [AnyView] {
    var segments: [AnyView] = []

    if footer.heardRepeats > 0, footer.showStatusRow {
      segments.append(AnyView(BubbleRepeatFooter(count: footer.heardRepeats, color: timeColor)))
    }

    if let sendTime = footer.sendTimeToShow {
      segments.append(AnyView(BubbleSendTimeFooter(
        date: sendTime,
        wasCorrected: footer.sendTimeWasCorrected,
        color: timeColor
      )))
    }
    if footer.showHop {
      segments.append(AnyView(BubbleHopCountFooter(hopCount: footer.hopCount)))
    }
    if let formattedPath = footer.formattedPath {
      segments.append(AnyView(BubblePathFooter(formattedPath: formattedPath)))
    }
    if let region = footer.regionToShow {
      segments.append(AnyView(BubbleRegionFooter(
        regionName: region,
        allowsWrap: dynamicTypeSize.isAccessibilitySize
      )))
    }

    if footer.showStatusRow {
      segments.append(AnyView(BubbleStatusFooter(footer: footer, color: timeColor, onRetry: onRetry)))
    }
    return segments
  }
}

private struct BubbleSendTimeFooter: View {
  let date: Date
  let wasCorrected: Bool
  let color: Color

  private var timeText: String {
    date.formatted(date: .omitted, time: .shortened)
  }

  private var accessibilityLabel: String {
    wasCorrected
      ? L10n.Chats.Chats.Message.SendTime.correctedAccessibilityLabel(timeText)
      : L10n.Chats.Chats.Message.SendTime.accessibilityLabel(timeText)
  }

  var body: some View {
    HStack(spacing: 2) {
      if wasCorrected {
        Image(systemName: correctedClockBadgeSymbol)
      }
      Text(timeText)
    }
    .font(.caption2)
    .foregroundStyle(color)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(accessibilityLabel)
  }
}

/// Rounded capsule chip shared by the hop / path / region / repeat footer
/// slots: compact interior padding with a hairline capsule border.
private extension View {
  func footerChip(color: Color) -> some View {
    padding(.horizontal, 6)
      .padding(.vertical, 2)
      .overlay(Capsule().strokeBorder(color.opacity(0.35), lineWidth: 1))
  }
}

private struct BubbleHopCountFooter: View {
  let hopCount: Int

  var body: some View {
    HStack(spacing: 2) {
      Image(systemName: "arrowshape.bounce.right")
      Text("\(hopCount)")
    }
    .font(.caption2)
    .foregroundStyle(.secondary)
    .footerChip(color: .secondary)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(L10n.Chats.Chats.Message.HopCount.accessibilityLabel(hopCount))
  }
}

/// Path chip that fits the comma-separated hop IDs onto a single line, dropping
/// nodes from the center (replacing them with an ellipsis) as space tightens so
/// the first and last hops always stay visible. `ViewThatFits` picks the longest
/// candidate that fits the offered width; it hugs that candidate rather than
/// filling the width, so it stays safe inside the self-sizing bubble cell.
private struct BubblePathFooter: View {
  let formattedPath: String

  /// Progressively collapsed renderings, longest first: the full path, then one
  /// fewer center node each step down to just the two endpoints.
  private var candidates: [String] {
    let nodes = formattedPath.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
    guard nodes.count > 2 else { return [formattedPath] }

    var result = [nodes.joined(separator: ",")]
    for shown in stride(from: nodes.count - 1, through: 2, by: -1) {
      let head = nodes.prefix((shown + 1) / 2).joined(separator: ",")
      let tail = nodes.suffix(shown / 2).joined(separator: ",")
      result.append("\(head)…\(tail)")
    }
    return result
  }

  var body: some View {
    HStack(spacing: 2) {
      Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
      ViewThatFits(in: .horizontal) {
        ForEach(candidates.indices, id: \.self) { index in
          Text(candidates[index]).lineLimit(1)
        }
      }
    }
    .font(.caption2.monospaced())
    .foregroundStyle(.secondary)
    .footerChip(color: .secondary)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(L10n.Chats.Chats.Message.Path.accessibilityLabel(formattedPath))
  }
}

private struct BubbleRegionFooter: View {
  let regionName: String
  let allowsWrap: Bool

  var body: some View {
    HStack(spacing: 2) {
      Image(systemName: "globe")
      Text(regionName)
        .lineLimit(allowsWrap ? nil : 1)
    }
    .font(.caption2)
    .foregroundStyle(.secondary)
    .footerChip(color: .secondary)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(L10n.Chats.Chats.Message.Region.accessibilityLabel(regionName))
  }
}

/// Repeat count heard back over the mesh: the repeat glyph and the count.
private struct BubbleRepeatFooter: View {
  let count: Int
  let color: Color

  private var accessibilityLabel: String {
    let word = count == 1
      ? L10n.Chats.Chats.Message.Repeat.singular
      : L10n.Chats.Chats.Message.Repeat.plural
    return "\(count) \(word)"
  }

  var body: some View {
    HStack(spacing: 2) {
      Image(systemName: "repeat")
      Text("\(count)")
    }
    .font(.caption2)
    .foregroundStyle(color)
    .footerChip(color: color)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(accessibilityLabel)
  }
}

/// Outgoing delivery-status icon shown trailing inside the bubble. A spinner
/// while in flight (with an `n/N` attempt count while retrying), a dotted check
/// for a queued channel broadcast, a filled check once delivered, and a tappable
/// retry glyph on failure.
private struct BubbleStatusFooter: View {
  let footer: MessageFooter
  let color: Color
  let onRetry: (() -> Void)?

  @State private var retryInvocationCounter: Int = 0

  var body: some View {
    Group {
      switch footer.status {
      case .pending, .sending:
        spinner
      case .retrying:
        HStack(spacing: 3) {
          spinner
          if footer.maxRetryAttempts > 0 {
            Text("\(footer.retryAttempt + 1)/\(footer.maxRetryAttempts)")
              .font(.caption2)
          }
        }
      case .sent:
        // A DM `.sent` only means the radio queued the packet; a missing ACK can
        // still flip it to `.failed`, so it reads as in-flight. A channel `.sent`
        // has no ACK and is terminal success.
        if footer.isChannelMessage {
          Image(systemName: "checkmark.circle.fill")
            .font(.caption2)
        } else {
          spinner
        }
      case .delivered:
        Image(systemName: "checkmark.circle.fill")
          .font(.caption2)
      case .failed:
        Button {
          retryInvocationCounter &+= 1
          onRetry?()
        } label: {
          Image(systemName: "arrow.clockwise")
            .font(.caption2)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: retryInvocationCounter)
      }
    }
    .foregroundStyle(color)
    .tint(color)
    .accessibilityLabel(MessageStatusText.text(for: footer))
  }

  private var spinner: some View {
    ProgressView()
      .controlSize(.mini)
  }
}

/// Localized status text for the outgoing delivery state. The visual footer now
/// renders this state as an icon, but the string is still surfaced by
/// `UnifiedMessageBubble.accessibilityMessageLabel` and the status icon's own
/// accessibility label so VoiceOver conveys the same meaning.
enum MessageStatusText {
  static func text(for footer: MessageFooter) -> String {
    switch footer.status {
    case .pending, .sending:
      return L10n.Chats.Chats.Message.Status.sending
    case .sent:
      guard footer.isChannelMessage else {
        return L10n.Chats.Chats.Message.Status.sending
      }
      var parts: [String] = []
      if footer.heardRepeats > 0 {
        let repeatWord = footer.heardRepeats == 1
          ? L10n.Chats.Chats.Message.Repeat.singular
          : L10n.Chats.Chats.Message.Repeat.plural
        parts.append("\(footer.heardRepeats) \(repeatWord)")
      }
      if footer.sendCount > 1 {
        parts.append(L10n.Chats.Chats.Message.Status.sentMultiple(footer.sendCount))
      } else {
        parts.append(L10n.Chats.Chats.Message.Status.sent)
      }
      return parts.joined(separator: " • ")
    case .delivered:
      var parts: [String] = []
      if footer.heardRepeats > 0 {
        let repeatWord = footer.heardRepeats == 1
          ? L10n.Chats.Chats.Message.Repeat.singular
          : L10n.Chats.Chats.Message.Repeat.plural
        parts.append("\(footer.heardRepeats) \(repeatWord)")
      }
      parts.append(L10n.Chats.Chats.Message.Status.delivered)
      if footer.sendCount > 1 {
        parts.append(L10n.Chats.Chats.Message.Status.sentMultiple(footer.sendCount))
      }
      return parts.joined(separator: " • ")
    case .failed:
      return L10n.Chats.Chats.Message.Status.failed
    case .retrying:
      let displayAttempt = footer.retryAttempt + 1
      let maxAttempts = footer.maxRetryAttempts
      if maxAttempts > 0 {
        return L10n.Chats.Chats.Message.Status.retryingAttempt(displayAttempt, maxAttempts)
      }
      return L10n.Chats.Chats.Message.Status.retrying
    }
  }
}
