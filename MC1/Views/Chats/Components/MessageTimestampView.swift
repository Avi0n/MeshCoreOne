import SwiftUI

/// Centered, time-only marker shown between message clusters when a time gap
/// breaks grouping. The day is carried separately by `MessageDayDividerView`.
struct MessageTimestampView: View {
  let date: Date

  var body: some View {
    Text(date.formatted(date: .omitted, time: .shortened))
      .font(.caption2)
      .foregroundStyle(.secondary)
      .frame(maxWidth: .infinity, alignment: .center)
  }
}

#Preview {
  VStack(spacing: 20) {
    MessageTimestampView(date: Date())
    MessageTimestampView(date: Date().addingTimeInterval(-3600)) // 1 hour ago
  }
  .padding()
}
