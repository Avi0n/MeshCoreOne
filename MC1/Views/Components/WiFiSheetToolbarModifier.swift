import SwiftUI

/// Shared toolbar for WiFi connection/edit sheets.
///
/// Provides cancel (leading), Done for iPad (top-bar-trailing when focused),
/// and Done for compact-width keyboard toolbar.
struct WiFiSheetToolbarModifier: ViewModifier {
  var focusedField: FocusState<WiFiField?>.Binding
  let isProcessing: Bool

  @Environment(\.dismiss) private var dismiss
  @Environment(\.horizontalSizeClass) private var horizontalSizeClass

  private var usesFullKeyboardInput: Bool {
    horizontalSizeClass == .regular
  }

  func body(content: Content) -> some View {
    content
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Localizable.Common.cancel) {
            focusedField.wrappedValue = nil
            dismiss()
          }
          .disabled(isProcessing)
        }
        ToolbarItem(placement: .topBarTrailing) {
          if usesFullKeyboardInput, focusedField.wrappedValue != nil {
            Button(L10n.Localizable.Common.done) {
              focusedField.wrappedValue = nil
            }
          }
        }
        if !usesFullKeyboardInput {
          ToolbarItemGroup(placement: .keyboard) {
            Spacer()
            Button(L10n.Localizable.Common.done) {
              focusedField.wrappedValue = nil
            }
          }
        }
      }
  }
}

extension View {
  func wifiSheetToolbar(
    focusedField: FocusState<WiFiField?>.Binding,
    isProcessing: Bool
  ) -> some View {
    modifier(WiFiSheetToolbarModifier(
      focusedField: focusedField,
      isProcessing: isProcessing
    ))
  }
}
