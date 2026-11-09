import SwiftUI

/// Full calendar date shown once per day, matching `NewMessagesDividerView`.
/// The date is a heading, the rules are hidden from VoiceOver, and the text wraps.
struct MessageDayDividerView: View {
  let date: Date

  var body: some View {
    let label = date.formatted(.dateTime.day().month(.wide).year())
    return HStack {
      VStack { Divider() }
        .accessibilityHidden(true)
      Text(label)
        .font(.caption2)
        .fontWeight(.semibold)
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
        .multilineTextAlignment(.center)
        .accessibilityAddTraits(.isHeader)
      VStack { Divider() }
        .accessibilityHidden(true)
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
