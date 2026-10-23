import Foundation

extension ContactDetailState {
  enum BusyOperation: Hashable, Sendable {
    case nickname, share, ping, clearMessages, avatar, favorite
  }

  func perform(_ action: Action) {
    reconcileOperationContext()
    guard hasPresentationOwnership else { return }
    if action != .refresh, actionTasks[action] != nil { return }
    if action == .discoverPath, pathViewModel.isDiscovering { return }
    actionTasks[action]?.cancel()
    let id = UUID()
    actionOperationIDs[action] = id
    actionTasks[action] = Task { [weak self] in
      guard let self else { return }
      defer { finishCommand(action, id: id) }
      guard hasPresentationOwnership, !Task.isCancelled else { return }
      switch action {
      case .block: await toggleBlocked()
      case .delete: await deleteContact()
      case .clearMessages: await clearMessages()
      case .share: await shareContact()
      case .ping: await pingRepeater()
      case .nickname: await saveNickname()
      case .refresh: await refreshContact()
      case .discoverPath: await pathViewModel.discoverPath(for: currentContact)
      case .editPath:
        let context = operationContext
        await pathViewModel.loadContacts(radioID: currentContact.radioID)
        guard accepts(context) else { return }
        pathViewModel.initializeEditablePath(from: currentContact)
        pathViewModel.showingPathEditor = true
      case .resetPath: await pathViewModel.resetPath(for: currentContact)
      }
    }
  }

  func startAvatarOperation(isWrite: Bool, work: @escaping @MainActor (ContactDetailState) async -> Void) {
    reconcileOperationContext()
    guard hasPresentationOwnership, !avatarOperationIsWrite else { return }
    avatarTask?.cancel()
    let id = UUID()
    avatarOperationID = id
    avatarOperationIsWrite = isWrite
    avatarTask = Task { [weak self] in
      guard let self else { return }
      defer { finishAvatarOperation(id: id) }
      guard hasPresentationOwnership, !Task.isCancelled else { return }
      await work(self)
    }
  }

  func reconcileOperationContext() {
    let current = operationContext
    if let ownedOperationContext, current != ownedOperationContext { retireOperations() }
    ownedOperationContext = current
  }

  func retireOperations() {
    favoriteTask?.cancel()
    favoriteTask = nil
    isSettingFavorite = false
    for task in actionTasks.values {
      task.cancel()
    }
    actionTasks.removeAll()
    actionOperationIDs.removeAll()
    if avatarTask != nil { avatarPickerItem = nil }
    avatarTask?.cancel()
    avatarTask = nil
    avatarOperationID = nil
    avatarOperationIsWrite = false
    isSaving = false
    isSharing = false
    showShareSuccess = false
    isPinging = false
    isClearingMessages = false
    isSavingAvatar = false
  }

  private func finishCommand(_ action: Action, id: UUID) {
    guard actionOperationIDs[action] == id else { return }
    actionTasks[action] = nil
    actionOperationIDs[action] = nil
    switch action {
    case .nickname: isSaving = false
    case .share:
      isSharing = false
      showShareSuccess = false
    case .ping: isPinging = false
    case .clearMessages: isClearingMessages = false
    default: break
    }
  }

  private func finishAvatarOperation(id: UUID) {
    guard avatarOperationID == id else { return }
    avatarTask = nil
    avatarOperationID = nil
    avatarOperationIsWrite = false
    isSavingAvatar = false
    avatarPickerItem = nil
  }

  func runWriteCommand(_ operation: BusyOperation, write: () async throws -> Void) async throws {
    #if DEBUG
      if let beforeCommandForTesting {
        try await beforeCommandForTesting(operation)
        return
      }
    #endif
    try await write()
  }

  var hasCommandOverrideForTesting: Bool {
    #if DEBUG
      beforeCommandForTesting != nil
    #else
      false
    #endif
  }

  #if DEBUG
    func commandTaskForTesting(_ action: Action) -> Task<Void, Never>? {
      actionTasks[action]
    }

    func replaceOperationContextForTesting(_ context: OperationContext) {
      operationContextForTesting = context
      reconcileOperationContext()
    }
  #endif
}
