import SwiftUI

/// Displays a relative timestamp using Apple's localized relative date formatting
struct RelativeTimestampText: View {
  let timestamp: UInt32

  private static let relativeFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter
  }()

  private static let weekThreshold: TimeInterval = 604_800
  private static let nowThreshold: TimeInterval = 60

  var body: some View {
    TimelineView(.everyMinute) { context in
      Text(Self.format(timestamp: timestamp, relativeTo: context.date))
        .font(.caption2)
        .foregroundStyle(.secondary)
    }
  }

  /// Formats a timestamp relative to the given date. Exposed for testing.
  static func format(timestamp: UInt32, relativeTo now: Date) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
    let interval = now.timeIntervalSince(date)

    if interval < nowThreshold {
      return L10n.Chats.Chats.Timestamp.now
    }

    if interval >= weekThreshold {
      return date.formatted(.dateTime.month(.abbreviated).day())
    }

    return relativeFormatter.localizedString(for: date, relativeTo: now)
  }
}

#Preview {
  VStack(alignment: .trailing, spacing: 8) {
    RelativeTimestampText(timestamp: UInt32(Date().timeIntervalSince1970))
    RelativeTimestampText(timestamp: UInt32(Date().addingTimeInterval(-120).timeIntervalSince1970))
    RelativeTimestampText(timestamp: UInt32(Date().addingTimeInterval(-3600).timeIntervalSince1970))
    RelativeTimestampText(timestamp: UInt32(Date().addingTimeInterval(-86400).timeIntervalSince1970))
    RelativeTimestampText(timestamp: UInt32(Date().addingTimeInterval(-259_200).timeIntervalSince1970))
  }
  .padding()
}
