import SwiftUI

/// ViewModifier for presenting error alerts with proper state binding.
/// `title` defaults to nil which uses the generic settings-style "Error" title.
/// Callers in user-action contexts (chat retry, etc.) may pass a more
/// specific title.
struct ErrorAlertModifier: ViewModifier {
    @Binding var errorMessage: String?
    let title: String?

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
        title: String? = nil
    ) -> some View {
        modifier(ErrorAlertModifier(errorMessage: errorMessage, title: title))
    }
}
