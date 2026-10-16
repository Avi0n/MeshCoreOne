import SwiftUI

/// Day separator shown once per calendar day in chat history. Rendered as a
/// centered, lined divider (matching `NewMessagesDividerView`) carrying the full
/// calendar date — e.g. "10 June 2026" — so each day's first message is clearly
/// anchored in time now that the per-cluster time marker lives inside the bubble.
struct MessageDayDividerView: View {
  let date: Date

  var body: some View {
    HStack {
      VStack { Divider() }
      Text(date.formatted(.dateTime.day().month(.wide).year()))
        .font(.caption2)
        .fontWeight(.semibold)
        .foregroundStyle(.secondary)
        .fixedSize()
      VStack { Divider() }
    }
    .padding(.vertical, 4)
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

#Preview("Last Year") {
  MessageDayDividerView(date: Calendar.current.date(byAdding: .year, value: -1, to: Date())!)
    .padding()
}
