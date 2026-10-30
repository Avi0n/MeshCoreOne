import MC1Services
import PhotosUI
import SwiftUI
import UIKit

@Observable
@MainActor
final class ContactDetailState {
  var currentContact: ContactDTO
  let showFromDirectChat: Bool
  var nickname: String
  var isEditingNickname = false
  var nicknameSelection: TextSelection?
  var nicknameFocused = false
  var scrollSection: ScrollSection?
  var showingBlockAlert = false
  var showingDeleteAlert = false
  var showingClearMessagesAlert = false
  var isClearingMessages = false
  var isSaving = false
  var isSettingFavorite = false
  var isFavorite: Bool {
    get { currentContact.isFavorite }
    set { requestFavorite(newValue) }
  }

  var errorMessage: String?
  let pathViewModel = PathManagementViewModel()
  var showRoomJoinSheet = false
  var activeSheet: ActiveSheet?
  var pendingSheet: ActiveSheet?
  var pendingSheetContext: OperationContext?
  var showQRShareSheet = false
  var showFullMap = false
  var isPinging = false
  var pingResult: PingResult?
  var isSharing = false
  var showShareSuccess = false
  var showAvatarSourceMenu = false
  var showAvatarPhotosPicker = false
  var showAvatarFileImporter = false
  var avatarPickerItem: PhotosPickerItem?
  var isSavingAvatar = false
  var pendingCropImage: UIImage?
  var cropRequest: AvatarCropRequest?
  private(set) var isEnded = false
  private(set) var rendererID: UUID?
  @ObservationIgnored private(set) var rendererRevision = 0
  @ObservationIgnored private var mountedRenderers: [UUID] = []

  @ObservationIgnored private var contactServiceProvider: @MainActor () -> ContactService? = { nil }
  @ObservationIgnored private var dataStoreProvider: @MainActor () -> PersistenceStore? = { nil }
  @ObservationIgnored private var advertisementServiceProvider: @MainActor () -> AdvertisementService? = { nil }
  @ObservationIgnored private var notificationServiceProvider: @MainActor () -> NotificationService? = { nil }
  @ObservationIgnored private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  @ObservationIgnored private var appStateProvider: @MainActor () -> AppState? = { nil }
  @ObservationIgnored private var onClose: () -> Void = {}
  @ObservationIgnored private var isCurrentPresentation: () -> Bool = { true }
  @ObservationIgnored private let onClearMessages: () -> Void
  @ObservationIgnored private var activityTask: Task<Void, Never>?
  @ObservationIgnored private var activityServicesVersion: Int?
  @ObservationIgnored private var isActive = false
  @ObservationIgnored private var hasLoadedContact = false
  @ObservationIgnored var favoriteTask: Task<Void, Never>?
  @ObservationIgnored var actionTasks: [Action: Task<Void, Never>] = [:]
  @ObservationIgnored var actionOperationIDs: [Action: UUID] = [:]
  @ObservationIgnored var ownedOperationContext: OperationContext?
  @ObservationIgnored var avatarOperationID: UUID?
  @ObservationIgnored var avatarOperationIsWrite = false
  @ObservationIgnored var avatarTask: Task<Void, Never>?
  #if DEBUG
    @ObservationIgnored var beforeCommandForTesting: (@MainActor (BusyOperation) async throws -> Void)?
    @ObservationIgnored var operationContextForTesting: OperationContext?
  #endif

  var hasPresentationOwnership: Bool {
    !isEnded && isCurrentPresentation()
  }

  var appState: AppState? {
    appStateProvider()
  }

  var contactService: ContactService? {
    hasPresentationOwnership ? contactServiceProvider() : nil
  }

  var dataStore: PersistenceStore? {
    hasPresentationOwnership ? dataStoreProvider() : nil
  }

  var advertisementService: AdvertisementService? {
    hasPresentationOwnership ? advertisementServiceProvider() : nil
  }

  var notificationService: NotificationService? {
    hasPresentationOwnership ? notificationServiceProvider() : nil
  }

  var connectedDevice: DeviceDTO? {
    hasPresentationOwnership ? connectedDeviceProvider() : nil
  }

  var contactTypeLabel: String {
    currentContact.type.localizedName
  }

  var operationContext: OperationContext {
    #if DEBUG
      if let operationContextForTesting { return operationContextForTesting }
    #endif
    return OperationContext(servicesVersion: appState?.servicesVersion, radioID: appState?.connectedDevice?.radioID)
  }

  var isVContact: Bool {
    guard let selfKey = connectedDevice?.publicKey else { return false }
    return VContactIdentity.isVContact(publicKey: currentContact.publicKey, selfPublicKey: selfKey)
  }

  init(contact: ContactDTO) {
    currentContact = contact
    nickname = contact.nickname ?? ""
    showFromDirectChat = false
    onClearMessages = {}
  }

  init(contact: ContactDTO, showFromDirectChat: Bool, onClearMessages: @escaping () -> Void) {
    currentContact = contact
    nickname = contact.nickname ?? ""
    self.showFromDirectChat = showFromDirectChat
    self.onClearMessages = onClearMessages
  }

  func configure(appState: AppState, onClose: @escaping () -> Void) {
    configure(appState: appState, isCurrentPresentation: { true }, onClose: onClose)
  }

  func configure(
    appState: AppState,
    isCurrentPresentation: @escaping () -> Bool,
    onClose: @escaping () -> Void
  ) {
    guard !isEnded else { return }
    self.isCurrentPresentation = isCurrentPresentation
    appStateProvider = { [weak appState] in appState }
    reconcileOperationContext()
    self.onClose = onClose
    let dependencies = Dependencies(
      contactService: { [weak appState] in appState?.services?.contactService },
      dataStore: { [weak appState] in appState?.services?.dataStore },
      advertisements: { [weak appState] in appState?.services?.advertisementService },
      notifications: { [weak appState] in appState?.services?.notificationService },
      connectedDevice: { [weak appState] in appState?.connectedDevice }
    )
    contactServiceProvider = dependencies.contactService
    dataStoreProvider = dependencies.dataStore
    advertisementServiceProvider = dependencies.advertisements
    notificationServiceProvider = dependencies.notifications
    connectedDeviceProvider = dependencies.connectedDevice
    pathViewModel.configure(
      dataStore: { [weak self] in self?.dataStore },
      contactService: { [weak self] in self?.contactService },
      connectedDevice: { [weak self] in self?.connectedDevice },
      onContactNeedsRefresh: { [weak self] in self?.perform(.refresh) }
    )
  }

  func setActive(_ active: Bool, appState: AppState) {
    reconcileOperationContext()
    guard hasPresentationOwnership, isActive != active || (active && activityServicesVersion != appState.servicesVersion) else { return }
    isActive = active
    activityTask?.cancel()
    activityTask = nil
    pathViewModel.cancelDiscovery()
    guard active else { return }
    let needsReload = !hasLoadedContact || activityServicesVersion != appState.servicesVersion
    activityServicesVersion = appState.servicesVersion
    let context = operationContext
    activityTask = Task { [weak self] in
      guard let self, accepts(context), isActive else { return }
      if needsReload {
        await pathViewModel.loadContacts(radioID: currentContact.radioID)
        guard accepts(context), isActive else { return }
        if let fresh = try? await contactService?.getContact(
          radioID: currentContact.radioID,
          publicKey: currentContact.publicKey
        ) {
          guard accepts(context), isActive else { return }
          updateContact(fresh)
        }
        guard accepts(context), isActive else { return }
        hasLoadedContact = true
      }
      guard let advertisements = advertisementService else { return }
      for await event in advertisements.events() {
        guard accepts(context), isActive else { return }
        switch event {
        case let .pathDiscoveryResponse(response) where response.matches(publicKey: currentContact.publicKey):
          pathViewModel.handleDiscoveryResponse(hopCount: response.outHopCount)
        case let .pathDiscoveryWriteFailed(response, error) where response.matches(publicKey: currentContact.publicKey):
          pathViewModel.handleDiscoveryWriteFailure(error)
        default:
          break
        }
      }
    }
  }

  func updateContact(_ contact: ContactDTO) {
    guard hasPresentationOwnership, contact.id == currentContact.id else { return }
    currentContact = contact
  }

  func accepts(_ context: OperationContext) -> Bool {
    hasPresentationOwnership && !Task.isCancelled && operationContext == context
  }

  func close() {
    guard hasPresentationOwnership else { return }
    onClose()
  }

  func didClearMessages() {
    guard hasPresentationOwnership else { return }
    onClearMessages()
    close()
  }

  func prepareRenderer(revision: Int) {
    guard hasPresentationOwnership, rendererRevision != revision else { return }
    rendererRevision = revision
    invalidateRenderer()
  }

  func mountRenderer(_ id: UUID, revision: Int = 0) {
    guard hasPresentationOwnership, rendererRevision == revision else { return }
    mountedRenderers.removeAll { $0 == id }
    mountedRenderers.append(id)
    rendererID = id
  }

  func unmountRenderer(_ id: UUID, revision: Int = 0) {
    guard rendererRevision == revision else { return }
    mountedRenderers.removeAll { $0 == id }
    // Recover the remaining mounted renderer because native navigation can briefly overlap renderers.
    if rendererID == id { rendererID = mountedRenderers.last }
  }

  func invalidateRenderer() {
    mountedRenderers = []
    rendererID = nil
  }

  func end() {
    guard !isEnded else { return }
    isEnded = true
    isActive = false
    invalidateRenderer()
    activityTask?.cancel()
    activityTask = nil
    retireOperations()
    pathViewModel.cancelDiscovery()
    pathViewModel.onContactNeedsRefresh = nil
    activeSheet = nil
    pendingSheet = nil
    cropRequest = nil
    pendingCropImage = nil
    showRoomJoinSheet = false
    showQRShareSheet = false
    showFullMap = false
    showAvatarPhotosPicker = false
    showAvatarFileImporter = false
  }
}
