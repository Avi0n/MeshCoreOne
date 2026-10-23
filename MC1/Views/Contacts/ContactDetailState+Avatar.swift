import MC1Services
import PhotosUI
import SwiftUI
import UIKit

extension ContactDetailState {
  private nonisolated static let avatarMaxDimension: CGFloat = 512
  private nonisolated static let avatarJPEGQuality: CGFloat = 0.8

  func loadAvatarPhoto(_ item: PhotosPickerItem?) {
    guard hasPresentationOwnership, let item else { return }
    startAvatarOperation(isWrite: false) { await $0.loadPickedAvatarPhoto(item) }
  }

  func importAvatarFile(_ result: Result<URL, Error>) {
    startAvatarOperation(isWrite: false) { await $0.handleAvatarFileImport(result) }
  }

  func completeAvatarCrop(_ image: UIImage) {
    guard hasPresentationOwnership, !avatarOperationIsWrite else { return }
    cropRequest = nil
    startAvatarOperation(isWrite: true) { await $0.saveAvatar(image: image) }
  }

  func removeAvatarPhoto() {
    startAvatarOperation(isWrite: true) { await $0.removeAvatar() }
  }

  private func loadPickedAvatarPhoto(_ item: PhotosPickerItem) async {
    let context = operationContext
    defer { if accepts(context) { avatarPickerItem = nil } }
    do {
      guard let data = try await item.loadTransferable(type: Data.self) else {
        guard accepts(context) else { return }
        errorMessage = L10n.Contacts.Contacts.Detail.Avatar.invalidImage
        return
      }
      guard accepts(context) else { return }
      await presentCropSheet(data: data, context: context)
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
  }

  private func handleAvatarFileImport(_ result: Result<URL, Error>) async {
    let context = operationContext
    switch result {
    case let .success(url):
      let didAccess = url.startAccessingSecurityScopedResource()
      defer { if didAccess { url.stopAccessingSecurityScopedResource() } }
      do {
        let data = try Data(contentsOf: url)
        await presentCropSheet(data: data, context: context)
      } catch {
        guard accepts(context) else { return }
        errorMessage = error.userFacingMessage
      }
    case let .failure(error):
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
  }

  private func presentCropSheet(data: Data, context: OperationContext) async {
    let decoded = await Task.detached(priority: .userInitiated) {
      ImageURLDetector.downsampledImage(from: data, maxPixelSize: AvatarCropGeometry.decodeMaxPixelSize)
    }.value
    guard accepts(context) else { return }
    guard let image = decoded else {
      errorMessage = L10n.Contacts.Contacts.Detail.Avatar.invalidImage
      return
    }
    pendingCropImage = image
    if !showAvatarPhotosPicker, !showAvatarFileImporter {
      presentPendingCropIfReady()
    }
  }

  func presentPendingCropIfReady() {
    guard hasPresentationOwnership, !showAvatarPhotosPicker, !showAvatarFileImporter, let image = pendingCropImage else { return }
    pendingCropImage = nil
    cropRequest = AvatarCropRequest(image: image)
  }

  private func saveAvatar(image: UIImage) async {
    let context = operationContext
    isSavingAvatar = true
    let processed = await Task.detached(priority: .userInitiated) {
      Self.processAvatarImage(image: image)
    }.value
    guard accepts(context) else { return }
    guard let processed else {
      errorMessage = L10n.Contacts.Contacts.Detail.Avatar.invalidImage
      isSavingAvatar = false
      return
    }
    do {
      try await runWriteCommand(.avatar) {
        try await contactService?.updateContactAvatar(contactID: currentContact.id, imageData: processed)
      }
      guard accepts(context) else { return }
      await refreshContact()
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
    guard accepts(context) else { return }
    isSavingAvatar = false
  }

  private func removeAvatar() async {
    let context = operationContext
    isSavingAvatar = true
    do {
      try await runWriteCommand(.avatar) {
        try await contactService?.updateContactAvatar(contactID: currentContact.id, imageData: nil)
      }
      guard accepts(context) else { return }
      await refreshContact()
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
    guard accepts(context) else { return }
    isSavingAvatar = false
  }

  /// Downscales before encoding so avatars stay small in the store.
  private nonisolated static func processAvatarImage(image: UIImage) -> Data? {
    let longest = max(image.size.width, image.size.height)
    guard longest > 0 else { return nil }
    if longest <= avatarMaxDimension {
      return image.jpegData(compressionQuality: avatarJPEGQuality)
    }
    let scale = avatarMaxDimension / longest
    let targetSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
    let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: targetSize)) }
    return resized.jpegData(compressionQuality: avatarJPEGQuality)
  }
}
