import SwiftUI

struct SettingsEditorPresenter: ViewModifier {
  let presentation: SettingsEditorPresentation

  func body(content: Content) -> some View {
    let request = presentation.request
    content.alert(title(for: request), isPresented: presentedBinding(for: request), presenting: request) { request in
      switch request.kind {
      case .regionAdd:
        TextField(L10n.Chats.Chats.ChannelInfo.Region.addRegionPlaceholder, text: draftBinding(for: request))
          .textInputAutocapitalization(.never)
          .autocorrectionDisabled()
        Button(L10n.Chats.Chats.ChannelInfo.Region.addSelected) {
          presentation.confirm(requestID: request.id)
        }
        cancelButton(for: request)
      case .deviceName:
        TextField(L10n.Settings.Node.name, text: draftBinding(for: request))
        cancelButton(for: request)
        Button(L10n.Localizable.Common.save) {
          presentation.confirm(requestID: request.id)
        }
      }
    } message: { request in
      if presentation.request?.id == request.id, let validationMessage = presentation.validationMessage {
        Text(validationMessage)
      }
    }
  }

  private func cancelButton(for request: SettingsEditorPresentation.Request) -> some View {
    Button(
      request.kind == .regionAdd ? L10n.Chats.Chats.Common.cancel : L10n.Localizable.Common.cancel,
      role: .cancel
    ) {
      presentation.cancel(requestID: request.id)
    }
  }

  private func title(for request: SettingsEditorPresentation.Request?) -> String {
    request?.kind == .regionAdd
      ? L10n.Chats.Chats.ChannelInfo.Region.addRegionTitle
      : L10n.Settings.Node.Alert.EditName.title
  }

  private func presentedBinding(for request: SettingsEditorPresentation.Request?) -> Binding<Bool> {
    Binding(
      get: { presentation.request?.id == request?.id && presentation.isPresented },
      set: { proposed in
        guard let request else { return }
        presentation.setPresented(proposed, requestID: request.id)
      }
    )
  }

  private func draftBinding(for request: SettingsEditorPresentation.Request) -> Binding<String> {
    Binding(
      get: { presentation.request?.id == request.id ? presentation.draft : "" },
      set: { presentation.updateDraft($0, requestID: request.id) }
    )
  }
}
