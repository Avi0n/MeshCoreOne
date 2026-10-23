import MC1Services
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ContactDetailPresenter: ViewModifier {
  let state: ContactDetailState?

  func body(content: Content) -> some View {
    content
      .errorAlert(binding(\.errorMessage, fallback: nil))
      .alert(L10n.Contacts.Contacts.Detail.Alert.Block.title, isPresented: binding(\.showingBlockAlert, fallback: false)) {
        Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
        Button(L10n.Contacts.Contacts.Action.block, role: .destructive) { state?.perform(.block) }
      } message: {
        Text(L10n.Contacts.Contacts.Detail.Alert.Block.message(state?.currentContact.displayName ?? ""))
      }
      .alert(
        L10n.Contacts.Contacts.Detail.Alert.Delete.title(state?.contactTypeLabel ?? ""),
        isPresented: binding(\.showingDeleteAlert, fallback: false)
      ) {
        Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
        Button(L10n.Contacts.Contacts.Common.delete, role: .destructive) { state?.perform(.delete) }
      } message: {
        Text(L10n.Contacts.Contacts.Detail.Alert.Delete.message(state?.currentContact.displayName ?? ""))
      }
      .alert(
        L10n.Contacts.Contacts.Detail.Alert.ClearMessages.title,
        isPresented: binding(\.showingClearMessagesAlert, fallback: false)
      ) {
        Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
        Button(L10n.Contacts.Contacts.Detail.clearMessages, role: .destructive) { state?.perform(.clearMessages) }
      } message: {
        Text(L10n.Contacts.Contacts.Detail.Alert.ClearMessages.message(state?.currentContact.displayName ?? ""))
      }
      .confirmationDialog(
        L10n.Contacts.Contacts.Detail.Avatar.chooseSource,
        isPresented: binding(\.showAvatarSourceMenu, fallback: false),
        titleVisibility: .visible
      ) {
        Button(L10n.Contacts.Contacts.Detail.Avatar.choosePhoto) { state?.showAvatarPhotosPicker = true }
        Button(L10n.Contacts.Contacts.Detail.Avatar.chooseFile) { state?.showAvatarFileImporter = true }
        if state?.currentContact.avatarImageData != nil {
          Button(L10n.Contacts.Contacts.Detail.Avatar.removePhoto, role: .destructive) { state?.removeAvatarPhoto() }
        }
        Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
      }
      .photosPicker(
        isPresented: binding(\.showAvatarPhotosPicker, fallback: false),
        selection: binding(\.avatarPickerItem, fallback: nil),
        matching: .images
      )
      .onChange(of: state?.avatarPickerItem) { _, item in state?.loadAvatarPhoto(item) }
      .onChange(of: state?.showAvatarPhotosPicker) { _, presented in
        if presented == false { state?.presentPendingCropIfReady() }
      }
      .fileImporter(isPresented: binding(\.showAvatarFileImporter, fallback: false), allowedContentTypes: [.image]) { result in
        state?.importAvatarFile(result)
      }
      .onChange(of: state?.showAvatarFileImporter) { _, presented in
        if presented == false { state?.presentPendingCropIfReady() }
      }
      .fullScreenCover(item: binding(\.cropRequest, fallback: nil)) { request in
        AvatarCropView(
          image: request.image,
          onCancel: { state?.cropRequest = nil },
          onComplete: { state?.completeAvatarCrop($0) }
        )
      }
      .sheet(isPresented: pathBinding(\.showingPathEditor, fallback: false), onDismiss: {
        guard let state, state.hasPresentationOwnership else { return }
        state.pathViewModel.insertionIntent = nil
      }) {
        if let state, state.hasPresentationOwnership {
          PathEditingSheet(viewModel: state.pathViewModel, contact: state.currentContact)
        }
      }
      .alert(L10n.Contacts.Contacts.Detail.Alert.pathError, isPresented: pathErrorPresented) {
        Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) {}
      } message: {
        Text(state?.pathViewModel.errorMessage ?? L10n.Contacts.Contacts.Common.errorOccurred)
      }
      .alert(
        L10n.Contacts.Contacts.Detail.Alert.pathDiscovery,
        isPresented: pathBinding(\.showDiscoveryResult, fallback: false)
      ) {
        Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) {}
      } message: {
        Text(state?.pathViewModel.discoveryResult?.description ?? "")
      }
      .sheet(isPresented: binding(\.showRoomJoinSheet, fallback: false)) { roomJoinSheet }
      .sheet(item: binding(\.activeSheet, fallback: nil), onDismiss: { state?.presentPendingSheet() }) { active in
        activeSheet(active)
      }
      .sheet(isPresented: binding(\.showRepeaterAdminAuth, fallback: false), onDismiss: { state?.presentAdminSession() }) {
        adminAuthenticationSheet
      }
      .sheet(isPresented: binding(\.showQRShareSheet, fallback: false)) {
        if let state, state.hasPresentationOwnership {
          ContactQRShareSheet(
            contactName: state.currentContact.name,
            publicKey: state.currentContact.publicKey,
            contactType: state.currentContact.type
          )
          .presentationDetents([.medium, .large])
          .presentationDragIndicator(.visible)
        }
      }
      .sheet(isPresented: binding(\.showFullMap, fallback: false)) {
        if let state, state.hasPresentationOwnership { ContactFullMapView(contact: state.currentContact) }
      }
  }

  private func binding<Value>(_ keyPath: ReferenceWritableKeyPath<ContactDetailState, Value>, fallback: Value) -> Binding<Value> {
    Binding(
      get: {
        guard let state, state.hasPresentationOwnership else { return fallback }
        return state[keyPath: keyPath]
      },
      set: { value in
        guard let state, state.hasPresentationOwnership else { return }
        state[keyPath: keyPath] = value
      }
    )
  }

  private func pathBinding<Value>(
    _ keyPath: ReferenceWritableKeyPath<PathManagementViewModel, Value>,
    fallback: Value
  ) -> Binding<Value> {
    Binding(
      get: {
        guard let state, state.hasPresentationOwnership else { return fallback }
        return state.pathViewModel[keyPath: keyPath]
      },
      set: { value in
        guard let state, state.hasPresentationOwnership else { return }
        state.pathViewModel[keyPath: keyPath] = value
      }
    )
  }

  private var pathErrorPresented: Binding<Bool> {
    Binding(
      get: { state?.hasPresentationOwnership == true && state?.pathViewModel.errorMessage != nil },
      set: { presented in
        guard !presented, let state, state.hasPresentationOwnership else { return }
        state.pathViewModel.errorMessage = nil
      }
    )
  }
}
