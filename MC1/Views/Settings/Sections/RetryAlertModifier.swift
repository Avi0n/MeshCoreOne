import MC1Services
import SwiftUI

/// Maximum number of retries before showing final error
private let maxRetries = 3

/// State container for retry alert with retry counting
@Observable
@MainActor
final class RetryAlertState {
  var isPresented: Bool = false
  var message: String = ""
  var retryCount: Int = 0
  var onRetry: (() -> Void)?
  var onMaxRetriesExceeded: (() -> Void)?

  /// Show a retry alert, incrementing the retry count
  func show(message: String, onRetry: @escaping () -> Void, onMaxRetriesExceeded: @escaping () -> Void) {
    retryCount += 1
    self.message = message
    self.onRetry = onRetry
    self.onMaxRetriesExceeded = onMaxRetriesExceeded
    isPresented = true
  }

  /// Reset retry count (call when operation succeeds or user cancels)
  func reset() {
    retryCount = 0
    onRetry = nil
    onMaxRetriesExceeded = nil
  }

  /// Whether max retries have been exceeded
  var isMaxRetriesExceeded: Bool {
    retryCount >= maxRetries
  }
}

/// ViewModifier for presenting retry alerts with retry counting
struct RetryAlertModifier: ViewModifier {
  @Bindable var state: RetryAlertState

  func body(content: Content) -> some View {
    content
      .alert(
        state.isMaxRetriesExceeded
          ? L10n.Settings.Alert.Retry.unableToSave
          : L10n.Settings.Alert.Retry.connectionError,
        isPresented: $state.isPresented
      ) {
        if state.isMaxRetriesExceeded {
          Button(L10n.Localizable.Common.ok) {
            state.onMaxRetriesExceeded?()
            state.reset()
          }
        } else {
          Button(L10n.Settings.Alert.Retry.retry) {
            state.onRetry?()
          }
          Button(L10n.Localizable.Common.cancel, role: .cancel) {
            state.reset()
          }
        }
      } message: {
        if state.isMaxRetriesExceeded {
          Text(L10n.Settings.Alert.Retry.ensureConnected)
        } else {
          Text(state.message)
        }
      }
  }
}

extension View {
  func retryAlert(_ state: RetryAlertState) -> some View {
    modifier(RetryAlertModifier(state: state))
  }
}
