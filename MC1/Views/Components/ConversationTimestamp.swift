import SwiftUI

struct ConversationTimestamp: View {
    let date: Date
    var font: Font = .caption
    var referenceDate: Date?

    var body: some View {
        if let referenceDate {
            Text(formattedDate(relativeTo: referenceDate))
                .font(font)
                .foregroundStyle(.secondary)
        } else {
            TimelineView(.everyMinute) { context in
                Text(formattedDate(relativeTo: context.date))
                    .font(font)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func formattedDate(relativeTo now: Date) -> String {
        let calendar = Calendar.current

        if calendar.isDate(date, inSameDayAs: now) {
            return date.formatted(date: .omitted, time: .shortened)
        } else if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
                  calendar.isDate(date, inSameDayAs: yesterday) {
            return date.formatted(.relative(presentation: .named))
        } else {
            return date.formatted(.dateTime.month(.abbreviated).day())
        }
    }
}
