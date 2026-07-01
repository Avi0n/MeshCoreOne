import SwiftUI

/// Day separator shown once per calendar day in chat history, carrying the
/// relative day so the time markers (`MessageTimestampView`) stay time-only.
/// Wrapped in `TimelineView` so "Today" rolls to "Yesterday" at midnight.
struct MessageDayDividerView: View {
  let date: Date

  var body: some View {
    TimelineView(.everyMinute) { context in
      Text(label(relativeTo: context.date))
        .font(.caption)
        .fontWeight(.semibold)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.vertical, 4)
    }
  }

  private func label(relativeTo now: Date) -> String {
    let calendar = Calendar.current

    if calendar.isDateInToday(date) {
      return L10n.Chats.Chats.Timestamp.today
    } else if calendar.isDateInYesterday(date) {
      return L10n.Chats.Chats.Timestamp.yesterday
    }

    // Weekday name for the past week, capped at 6 days so it cannot collide
    // with today's weekday (which 7 days ago would share).
    let dayOffset = calendar.dateComponents(
      [.day],
      from: calendar.startOfDay(for: date),
      to: calendar.startOfDay(for: now)
    ).day ?? 0

    if (2...6).contains(dayOffset) {
      return date.formatted(.dateTime.weekday(.wide))
    } else if calendar.isDate(date, equalTo: now, toGranularity: .year) {
      return date.formatted(.dateTime.month(.abbreviated).day())
    } else {
      return date.formatted(.dateTime.month(.abbreviated).day().year())
    }
  }
}

#Preview("Today") {
  MessageDayDividerView(date: Date())
    .padding()
}

#Preview("Yesterday") {
  MessageDayDividerView(date: Calendar.current.date(byAdding: .day, value: -1, to: Date())!)
    .padding()
}

#Preview("Weekday") {
  MessageDayDividerView(date: Calendar.current.date(byAdding: .day, value: -3, to: Date())!)
    .padding()
}

#Preview("Last Year") {
  MessageDayDividerView(date: Calendar.current.date(byAdding: .year, value: -1, to: Date())!)
    .padding()
}
