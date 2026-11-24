import SwiftUI

/// Displays a relative timestamp using Apple's localized relative date formatting
struct RelativeTimestampText: View {
  let date: Date
  var referenceDate: Date?

  /// Wire-format unix seconds (contact/message timestamps from the radio).
  init(timestamp: UInt32, referenceDate: Date? = nil) {
    self.date = Date(timeIntervalSince1970: TimeInterval(timestamp))
    self.referenceDate = referenceDate
  }

  /// Receiver-clock or other already-resolved `Date` values.
  init(date: Date, referenceDate: Date? = nil) {
    self.date = date
    self.referenceDate = referenceDate
  }

  private static let relativeFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter
  }()

  private static let weekThreshold: TimeInterval = 604_800
  private static let nowThreshold: TimeInterval = 60

  var body: some View {
    if let referenceDate {
      timestampText(relativeTo: referenceDate)
    } else {
      TimelineView(.everyMinute) { context in
        timestampText(relativeTo: context.date)
      }
    }
  }

  private func timestampText(relativeTo now: Date) -> some View {
    Text(Self.format(date: date, relativeTo: now))
      .font(.caption2)
      .foregroundStyle(.secondary)
  }

  /// Formats a date relative to the given reference. Exposed for testing.
  static func format(date: Date, relativeTo now: Date) -> String {
    let interval = now.timeIntervalSince(date)

    if interval < nowThreshold {
      return L10n.Chats.Chats.Timestamp.now
    }

    if interval >= weekThreshold {
      return date.formatted(.dateTime.month(.abbreviated).day())
    }

    return relativeFormatter.localizedString(for: date, relativeTo: now)
  }

  /// Formats a wire-format unix timestamp relative to the given date. Exposed for testing.
  static func format(timestamp: UInt32, relativeTo now: Date) -> String {
    format(date: Date(timeIntervalSince1970: TimeInterval(timestamp)), relativeTo: now)
  }
}

#Preview {
  VStack(alignment: .trailing, spacing: 8) {
    RelativeTimestampText(date: Date())
    RelativeTimestampText(date: Date().addingTimeInterval(-120))
    RelativeTimestampText(date: Date().addingTimeInterval(-3600))
    RelativeTimestampText(date: Date().addingTimeInterval(-86400))
    RelativeTimestampText(date: Date().addingTimeInterval(-259_200))
  }
  .padding()
}
