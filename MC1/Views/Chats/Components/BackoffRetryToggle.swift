import SwiftUI

/// Opts the next DM into backoff rounds: if no ACK arrives after the normal retries,
/// it sends again after 2, 4 and 8 minutes before failing. Clears itself after the send.
struct BackoffRetryToggle: View {
  @Binding var isArmed: Bool

  private var idleIconColor: Color {
    if #available(iOS 26.0, *) { .primary } else { Color(.systemGray) }
  }

  var body: some View {
    Button {
      isArmed.toggle()
    } label: {
      Image(systemName: "arrow.triangle.2.circlepath")
        .font(.system(size: 18, weight: .semibold))
        .foregroundStyle(isArmed ? Color.white : idleIconColor)
        .frame(width: ChatInputMetrics.controlHeight, height: ChatInputMetrics.controlHeight)
        .toggleBackground(isArmed: isArmed)
        .contentShape(Circle())
    }
    .buttonStyle(.plain)
    .sensoryFeedback(.selection, trigger: isArmed)
    .accessibilityLabel(L10n.Chats.Chats.Input.BackoffRetry.label)
    .accessibilityHint(L10n.Chats.Chats.Input.BackoffRetry.hint)
    .accessibilityAddTraits(isArmed ? .isSelected : [])
  }
}

private extension View {
  @ViewBuilder
  func toggleBackground(isArmed: Bool) -> some View {
    if #available(iOS 26.0, *) {
      glassEffect(isArmed ? .regular.tint(.orange).interactive() : .regular.interactive(), in: .circle)
    } else {
      background(isArmed ? Color.orange : Color(.systemGray5), in: Circle())
    }
  }
}

#Preview {
  @Previewable @State var armed = false
  BackoffRetryToggle(isArmed: $armed)
    .padding()
}
