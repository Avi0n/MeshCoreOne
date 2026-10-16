import SwiftUI

/// ViewModifier for presenting error alerts with proper state binding.
/// `title` defaults to nil which uses the generic settings-style "Error" title.
/// `retryAction`, when provided, adds a "Try Again" button alongside OK — used by
/// action contexts (product load, chat retry) where a user-initiated retry is meaningful.
struct ErrorAlertModifier: ViewModifier {
  @Binding var errorMessage: String?
  let title: String?
  let retryAction: (() -> Void)?

  private var resolvedTitle: String {
    title ?? L10n.Settings.Alert.Error.title
  }

  private var isPresented: Binding<Bool> {
    Binding(
      get: { errorMessage != nil },
      set: { if !$0 { errorMessage = nil } }
    )
  }

  func body(content: Content) -> some View {
    content
      .alert(resolvedTitle, isPresented: isPresented) {
        if let retryAction {
          Button(L10n.Localizable.Common.tryAgain) {
            errorMessage = nil
            retryAction()
          }
        }
        Button(L10n.Localizable.Common.ok) {
          errorMessage = nil
        }
      } message: {
        Text(errorMessage ?? "")
      }
  }
}

extension View {
  func errorAlert(
    _ errorMessage: Binding<String?>,
    title: String? = nil,
    retryAction: (() -> Void)? = nil
  ) -> some View {
    modifier(ErrorAlertModifier(errorMessage: errorMessage, title: title, retryAction: retryAction))
  }
}
