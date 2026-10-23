import SwiftUI

struct PingResultRow: View {
  let result: PingResult

  var body: some View {
    switch result {
    case let .success(latencyMs, snrThere, snrBack):
      let snrFormat = FloatingPointFormatStyle<Double>.number.precision(.fractionLength(2))
      Label {
        Text("\(latencyMs) ms  ·  SNR ↑ \(snrThere, format: snrFormat) dB  ↓ \(snrBack, format: snrFormat) dB")
          .font(.subheadline)
      } icon: {
        Image(systemName: "checkmark.circle.fill")
          .foregroundStyle(.green)
      }
      .accessibilityElement(children: .combine)
      .accessibilityLabel(L10n.Contacts.Contacts.Detail.pingSuccessLabel(latencyMs, Int(snrThere), Int(snrBack)))
    case let .error(message):
      Label {
        Text(message)
          .font(.subheadline)
          .foregroundStyle(.secondary)
      } icon: {
        Image(systemName: "exclamationmark.circle.fill")
          .foregroundStyle(.orange)
      }
      .accessibilityElement(children: .combine)
      .accessibilityLabel(L10n.Contacts.Contacts.Detail.pingFailureLabel(message))
    }
  }
}
