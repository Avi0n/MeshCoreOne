import SwiftUI

/// A centered button label that shows a loading spinner, a success checkmark, or idle content.
///
/// Used for buttons that trigger async firmware commands and need visual feedback.
/// The success checkmark auto-animates in with a scale+opacity transition.
struct AsyncActionLabel<Idle: View>: View {
    let isLoading: Bool
    let showSuccess: Bool
    @ViewBuilder let idle: () -> Idle

    var body: some View {
        HStack {
            Spacer()
            if isLoading {
                ProgressView()
            } else if showSuccess {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .transition(.scale.combined(with: .opacity))
            } else {
                idle()
            }
            Spacer()
        }
        .animation(.default, value: showSuccess)
    }
}
