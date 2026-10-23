import MC1Services
import SwiftUI

extension ContactDetailState {
  private static let shareFeedbackDuration = Duration.seconds(1.5)

  func presentPendingSheet() {
    defer { pendingSheet = nil; pendingSheetContext = nil }
    guard let context = pendingSheetContext, accepts(context), let next = pendingSheet else { return }
    activeSheet = next
  }

  func presentAdminSession() {
    defer { adminSession = nil; adminSessionContext = nil }
    guard let context = adminSessionContext, accepts(context), let session = adminSession else { return }
    if session.isAdmin {
      activeSheet = .adminSettings(session)
    } else if session.isRoom {
      activeSheet = .roomStatus(session)
    } else {
      activeSheet = .repeaterStatus(session)
    }
  }

  func showTelemetry() {
    guard hasPresentationOwnership else { return }
    activeSheet = currentContact.type == .chat ? .nodeTelemetry(currentContact) : .nodeAuth
  }

  func showRemoteCLI() {
    guard hasPresentationOwnership else { return }
    activeSheet = .remoteCLI(currentContact)
  }

  func showAdminAccess() {
    guard hasPresentationOwnership else { return }
    adminSession = nil
    showRepeaterAdminAuth = true
  }

  func requestFavorite(_ favorite: Bool) {
    reconcileOperationContext()
    guard hasPresentationOwnership, !isSettingFavorite, favorite != isFavorite else { return }
    let context = operationContext
    isSettingFavorite = true
    favoriteTask = Task { [weak self] in
      guard let self else { return }
      defer {
        if accepts(context) {
          isSettingFavorite = false
          favoriteTask = nil
        }
      }
      guard accepts(context) else { return }
      await setFavorite(favorite)
    }
  }

  func setFavorite(_ favorite: Bool) async {
    let service = contactService
    guard service != nil || hasCommandOverrideForTesting else {
      errorMessage = L10n.Contacts.Contacts.Detail.Error.servicesUnavailable
      return
    }
    let context = operationContext
    errorMessage = nil
    do {
      var updated = currentContact.with(isFavorite: favorite)
      try await runWriteCommand(.favorite) {
        if let service {
          updated = try await service.setContactFavorite(currentContact.id, isFavorite: favorite)
        }
      }
      guard accepts(context) else { return }
      updateContact(updated)
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
  }

  func toggleBlocked() async {
    let context = operationContext
    do {
      try await contactService?.updateContactPreferences(
        contactID: currentContact.id,
        isBlocked: !currentContact.isBlocked
      )
      guard accepts(context) else { return }
      await refreshContact()
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
  }

  func deleteContact() async {
    let context = operationContext
    let contact = currentContact
    guard let appState, let service = contactService else { return }
    do {
      try await service.removeContact(radioID: contact.radioID, publicKey: contact.publicKey)
      didRemoveContact(contact.id, navigation: appState.navigation, context: context)
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
  }

  func didRemoveContact(_ id: UUID, navigation: NavigationCoordinator, context: OperationContext) {
    // Clear the matching selection after retirement because the same contact may have been reopened.
    navigation.clearSelectedContact(matching: id)
    guard accepts(context) else { return }
    close()
  }

  func clearMessages() async {
    let service = contactService
    guard service != nil || hasCommandOverrideForTesting else {
      errorMessage = L10n.Contacts.Contacts.Detail.Error.servicesUnavailable
      return
    }
    let context = operationContext
    let contactID = currentContact.id
    let notifications = notificationService
    isClearingMessages = true
    errorMessage = nil
    do {
      try await runWriteCommand(.clearMessages) {
        try await service?.clearContactMessages(contactID: contactID)
      }
      await notifications?.removeDeliveredNotifications(forContactID: contactID)
      await notifications?.updateBadgeCount()
      guard accepts(context) else { return }
      didClearMessages()
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
      isClearingMessages = false
    }
  }

  func shareContact() async {
    guard !isSharing else { return }
    let context = operationContext
    isSharing = true
    do {
      try await runWriteCommand(.share) {
        try await contactService?.shareContact(publicKey: currentContact.publicKey)
      }
      guard accepts(context) else { return }
      isSharing = false
      withAnimation { showShareSuccess = true }
      try await Task.sleep(for: Self.shareFeedbackDuration)
      guard accepts(context) else { return }
      withAnimation { showShareSuccess = false }
    } catch ContactServiceError.shareContactUnavailable {
      guard accepts(context) else { return }
      isSharing = false
      errorMessage = L10n.Contacts.Contacts.Detail.shareContactUnavailable
    } catch {
      guard accepts(context) else { return }
      isSharing = false
      errorMessage = error.userFacingMessage
    }
  }

  func pingRepeater() async {
    guard !isPinging, let appState else { return }
    let context = operationContext
    isPinging = true
    pingResult = nil
    let result: PingResult
    #if DEBUG
      if let beforeCommandForTesting {
        do {
          try await beforeCommandForTesting(.ping)
          result = .error(L10n.Contacts.Contacts.Detail.pingNoResponse)
        } catch {
          guard accepts(context) else { return }
          errorMessage = error.userFacingMessage
          return
        }
      } else {
        result = await PingHelper.zeroHopPing(contact: currentContact, appState: appState)
      }
    #else
      result = await PingHelper.zeroHopPing(contact: currentContact, appState: appState)
    #endif
    guard accepts(context) else { return }
    pingResult = result
    isPinging = false
  }

  func refreshContact() async {
    let context = operationContext
    if let updated = try? await dataStore?.fetchContact(id: currentContact.id) {
      guard accepts(context) else { return }
      updateContact(updated)
    }
  }

  func saveNickname() async {
    guard !isSaving else { return }
    let context = operationContext
    let draft = nickname
    isSaving = true
    do {
      try await runWriteCommand(.nickname) {
        try await contactService?.updateContactPreferences(contactID: currentContact.id, nickname: draft)
      }
      guard accepts(context) else { return }
      await refreshContact()
    } catch {
      guard accepts(context) else { return }
      errorMessage = error.userFacingMessage
    }
    guard accepts(context) else { return }
    isEditingNickname = false
    nicknameFocused = false
    isSaving = false
  }
}
