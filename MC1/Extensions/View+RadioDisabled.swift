import SwiftUI
import MC1Services

extension View {
    /// Disables the view when radio connection is not ready, applying visual
    /// feedback to indicate unavailability.
    ///
    /// - Parameters:
    ///   - connectionState: The current connection state from appState
    ///   - otherCondition: Additional condition that should disable the view
    ///
    /// Example:
    /// ```swift
    /// Button("Save") { }
    ///     .radioDisabled(for: appState.connectionState, or: isSaving)
    /// ```
    @ViewBuilder
    func radioDisabled(for connectionState: DeviceConnectionState, or otherCondition: Bool = false) -> some View {
        let isNotReady = connectionState != .ready
        if isNotReady {
            self
                .disabled(true)
                .foregroundStyle(.secondary)
                .accessibilityHint("Requires radio connection")
        } else {
            self.disabled(otherCondition)
        }
    }
}
