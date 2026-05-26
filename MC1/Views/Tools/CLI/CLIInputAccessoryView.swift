import SwiftUI

struct CLIInputAccessoryView: View {
    let isWaiting: Bool
    var showSessionsButton: Bool = true
    let onHistoryUp: () -> Void
    let onHistoryDown: () -> Void
    let onTabComplete: () -> Void
    let onMoveLeft: () -> Void
    let onMoveRight: () -> Void
    let onPaste: () -> Void
    let onSessions: () -> Void
    let onCancel: () -> Void
    let onDismiss: () -> Void

    @State private var showCancel = false

    var body: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    Button(action: onTabComplete) {
                        Image(systemName: "arrow.right.to.line")
                    }
                    .accessibilityLabel(L10n.Tools.Tools.Cli.tabComplete)

                    Button(action: onHistoryUp) {
                        Image(systemName: "arrow.up")
                    }
                    .accessibilityLabel(L10n.Tools.Tools.Cli.historyUp)

                    Button(action: onHistoryDown) {
                        Image(systemName: "arrow.down")
                    }
                    .accessibilityLabel(L10n.Tools.Tools.Cli.historyDown)

                    Button(action: onMoveLeft) {
                        Image(systemName: "arrow.left")
                    }
                    .accessibilityLabel(L10n.Tools.Tools.Cli.cursorLeft)

                    Button(action: onMoveRight) {
                        Image(systemName: "arrow.right")
                    }
                    .accessibilityLabel(L10n.Tools.Tools.Cli.cursorRight)

                    Button(action: onPaste) {
                        Image(systemName: "doc.on.clipboard")
                    }
                    .accessibilityLabel(L10n.Tools.Tools.Cli.paste)

                    if showSessionsButton {
                        Divider()
                            .frame(height: 20)

                        Button(action: onSessions) {
                            Image(systemName: "rectangle.stack")
                        }
                        .accessibilityLabel(L10n.Tools.Tools.Cli.sessions)
                    }

                    Button(action: onCancel) {
                        Image(systemName: "stop.circle")
                            .foregroundStyle(showCancel ? .red : .secondary)
                    }
                    .disabled(!showCancel)
                    .accessibilityLabel(L10n.Tools.Tools.Cli.cancelOperation)
                }
                .font(.body)
            }

            Spacer()

            Button(action: onDismiss) {
                Image(systemName: "keyboard.chevron.compact.down")
            }
            .font(.body)
            .accessibilityLabel(L10n.Tools.Tools.Cli.dismiss)
        }
        .padding(.horizontal)
        .frame(height: 44)
        .task(id: isWaiting) {
            if isWaiting {
                try? await Task.sleep(for: .milliseconds(150))
                showCancel = true
            } else {
                showCancel = false
            }
        }
        .background {
            if #available(iOS 26.0, *) {
                Rectangle().fill(.clear).glassEffect()
            } else {
                Rectangle().fill(.ultraThinMaterial)
            }
        }
        .padding(.horizontal, 4)
    }
}

#Preview {
    CLIInputAccessoryView(
        isWaiting: false,
        onHistoryUp: {},
        onHistoryDown: {},
        onTabComplete: {},
        onMoveLeft: {},
        onMoveRight: {},
        onPaste: {},
        onSessions: {},
        onCancel: {},
        onDismiss: {}
    )
}
