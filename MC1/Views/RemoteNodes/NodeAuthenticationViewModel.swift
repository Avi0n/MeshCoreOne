import CoreLocation
import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "NodeAuthenticationViewModel")

@Observable
@MainActor
final class NodeAuthenticationViewModel {
  struct Dependencies {
    let roomServerService: @MainActor () -> RoomServerService?
    let remoteNodeService: @MainActor () -> RemoteNodeService?
    let repeaterAdminService: @MainActor () -> RepeaterAdminService?
    let contactService: @MainActor () -> ContactService?
    let dataStore: @MainActor () -> PersistenceStore?
    let connectionGeneration: @MainActor () -> UUID?
    let bestAvailableLocation: @MainActor () -> CLLocation?
  }

  private static let countdownTickInterval: Duration = .seconds(1)
  private static let maxPasswordBytes = ProtocolLimits.maxRemotePasswordBytes

  let role: RemoteNodeRole
  let requireAdmin: Bool

  var currentContact: ContactDTO
  var password: String = ""
  var rememberPassword = true
  var useFloodRouting: Bool
  var didManualReset = false
  var isRetryingViaFlood = false
  var isAuthenticating = false
  var errorMessage: String?
  var pathViewModel = NodeAuthPathViewModel()

  var authSecondsRemaining: Int?
  private var authStartTime: Date?
  private var authTimeoutSeconds: Int?
  private var countdownTask: Task<Void, Never>?
  private(set) var authenticationTask: Task<Void, Never>?
  private var authEpoch = Epoch()

  private var roomServerServiceProvider: @MainActor () -> RoomServerService? = { nil }
  private var remoteNodeServiceProvider: @MainActor () -> RemoteNodeService? = { nil }
  private var repeaterAdminServiceProvider: @MainActor () -> RepeaterAdminService? = { nil }
  private var contactServiceProvider: @MainActor () -> ContactService? = { nil }
  private var dataStoreProvider: @MainActor () -> PersistenceStore? = { nil }
  private var connectionGenerationProvider: @MainActor () -> UUID? = { nil }
  private var bestAvailableLocationProvider: @MainActor () -> CLLocation? = { nil }

  private var roomServerService: RoomServerService? {
    roomServerServiceProvider()
  }

  private var remoteNodeService: RemoteNodeService? {
    remoteNodeServiceProvider()
  }

  private var repeaterAdminService: RepeaterAdminService? {
    repeaterAdminServiceProvider()
  }

  private var contactService: ContactService? {
    contactServiceProvider()
  }

  private var dataStore: PersistenceStore? {
    dataStoreProvider()
  }

  private var connectionGeneration: UUID? {
    connectionGenerationProvider()
  }

  private var bestAvailableLocation: CLLocation? {
    bestAvailableLocationProvider()
  }

  init(contact: ContactDTO, role: RemoteNodeRole, requireAdmin: Bool) {
    currentContact = contact
    self.role = role
    self.requireAdmin = requireAdmin
    useFloodRouting = contact.isFloodRouted
  }

  func configure(_ dependencies: Dependencies) {
    roomServerServiceProvider = dependencies.roomServerService
    remoteNodeServiceProvider = dependencies.remoteNodeService
    repeaterAdminServiceProvider = dependencies.repeaterAdminService
    contactServiceProvider = dependencies.contactService
    dataStoreProvider = dependencies.dataStore
    connectionGenerationProvider = dependencies.connectionGeneration
    bestAvailableLocationProvider = dependencies.bestAvailableLocation
  }

  static func shouldShowWaitingForRadio(
    isAuthenticating: Bool,
    authSecondsRemaining: Int?,
    isRetryingViaFlood: Bool
  ) -> Bool {
    isAuthenticating && authSecondsRemaining == nil && !isRetryingViaFlood
  }

  var isWaitingForRadio: Bool {
    Self.shouldShowWaitingForRadio(
      isAuthenticating: isAuthenticating,
      authSecondsRemaining: authSecondsRemaining,
      isRetryingViaFlood: isRetryingViaFlood
    )
  }

  func loadSavedCredentials() async {
    guard let generation = connectionGeneration, let remoteNodeService else { return }
    if let saved = await remoteNodeService.retrievePassword(forContact: currentContact) {
      guard accepts(generation: generation), password.isEmpty, !isAuthenticating else { return }
      password = saved
    }
  }

  func loadResolvedPath() async {
    guard !currentContact.isFloodRouted, currentContact.pathHopCount > 0,
          let generation = connectionGeneration else { return }
    await pathViewModel.load(
      contact: currentContact,
      dataStore: dataStore,
      radioID: currentContact.radioID,
      userLocation: bestAvailableLocation,
      isCurrent: { [self] in accepts(generation: generation) }
    )
  }

  func cancelAuthentication() {
    authEpoch.bump()
    authenticationTask?.cancel()
    authenticationTask = nil
    isAuthenticating = false
    isRetryingViaFlood = false
    cleanupCountdownState()
  }

  func authenticate(onSuccess: @escaping @MainActor (RemoteNodeSessionDTO) -> Void) {
    guard password.utf8.count <= Self.maxPasswordBytes else {
      errorMessage = RemoteNodeError.invalidPasswordLength.userFacingMessage
      return
    }
    guard let generation = connectionGeneration else {
      errorMessage = RemoteNodeError.notConnected.userFacingMessage
      return
    }
    let submittedPassword = password
    let submittedRememberPassword = rememberPassword
    cancelAuthentication()
    let ticket = authEpoch.ticket()
    errorMessage = nil
    isAuthenticating = true

    authenticationTask = Task {
      await runAuthentication(
        generation: generation,
        submittedPassword: submittedPassword,
        submittedRememberPassword: submittedRememberPassword,
        ticket: ticket,
        onSuccess: onSuccess
      )
    }
  }

  private func runAuthentication(
    generation: UUID,
    submittedPassword: String,
    submittedRememberPassword: Bool,
    ticket: Epoch.Ticket,
    onSuccess: @MainActor (RemoteNodeSessionDTO) -> Void
  ) async {
    do {
      guard accepts(generation: generation, ticket: ticket) else { return }

      let pathLength: UInt8
      if useFloodRouting, !currentContact.isFloodRouted, !didManualReset {
        guard let contactService else { throw RemoteNodeError.notConnected }
        try await contactService.resetPath(
          radioID: currentContact.radioID,
          publicKey: currentContact.publicKey
        )
        didManualReset = true
        if accepts(generation: generation, ticket: ticket),
           let liveContactService = self.contactService,
           let fresh = try? await liveContactService.getContact(
             radioID: currentContact.radioID,
             publicKey: currentContact.publicKey
           ),
           accepts(generation: generation, ticket: ticket) {
          currentContact = fresh
        }
        guard accepts(generation: generation, ticket: ticket) else { return }
        pathLength = PacketBuilder.floodPathSentinel
      } else if useFloodRouting || didManualReset {
        pathLength = PacketBuilder.floodPathSentinel
      } else {
        pathLength = currentContact.outPathLength
      }

      let session: RemoteNodeSessionDTO
      do {
        session = try await performLogin(
          generation: generation,
          password: submittedPassword,
          rememberPassword: submittedRememberPassword,
          ticket: ticket,
          pathLength: pathLength,
          samePasswordExemption: false
        )
      } catch RemoteNodeError.timeout {
        guard accepts(generation: generation, ticket: ticket) else { return }
        guard pathLength != PacketBuilder.floodPathSentinel else {
          throw RemoteNodeError.timeout
        }
        guard let contactService else { throw RemoteNodeError.notConnected }
        do {
          session = try await contactService.attemptFloodFallback(
            radioID: currentContact.radioID,
            publicKey: currentContact.publicKey,
            rethrowIfUnavailable: RemoteNodeError.timeout
          ) { [weak self] in
            guard let self else { throw CancellationError() }
            guard await MainActor.run(body: { self.accepts(generation: generation, ticket: ticket) }) else {
              throw CancellationError()
            }
            await MainActor.run {
              self.isRetryingViaFlood = true
              self.cleanupCountdownState()
            }
            return try await self.performLogin(
              generation: generation,
              password: submittedPassword,
              rememberPassword: submittedRememberPassword,
              ticket: ticket,
              pathLength: PacketBuilder.floodPathSentinel,
              samePasswordExemption: true
            )
          }
        } catch {
          if accepts(generation: generation, ticket: ticket),
             let liveContactService = self.contactService,
             let fresh = try? await liveContactService.getContact(
               radioID: currentContact.radioID,
               publicKey: currentContact.publicKey
             ),
             accepts(generation: generation, ticket: ticket) {
            currentContact = fresh
            await pathViewModel.load(
              contact: fresh,
              dataStore: dataStore,
              radioID: fresh.radioID,
              userLocation: bestAvailableLocation,
              isCurrent: { [self] in accepts(generation: generation, ticket: ticket) }
            )
          }
          throw error
        }
      }

      guard accepts(generation: generation, ticket: ticket) else { return }
      authenticationTask = nil
      isAuthenticating = false
      isRetryingViaFlood = false
      cleanupCountdownState()
      onSuccess(session)
    } catch is CancellationError {
      guard accepts(generation: generation, ticket: ticket) else { return }
      cancelAuthentication()
    } catch RemoteNodeError.timeout {
      guard accepts(generation: generation, ticket: ticket) else { return }
      cancelAuthentication()
      errorMessage = L10n.RemoteNodes.RemoteNodes.Auth.noReply
    } catch {
      guard accepts(generation: generation, ticket: ticket) else { return }
      cancelAuthentication()
      if role == .sensor, requireAdmin, case RemoteNodeError.permissionDenied = error {
        errorMessage = L10n.RemoteNodes.RemoteNodes.Sensor.adminRequired
      } else {
        errorMessage = error.userFacingMessage
      }
    }
  }

  private func performLogin(
    generation: UUID,
    password: String,
    rememberPassword: Bool,
    ticket: Epoch.Ticket,
    pathLength: UInt8,
    samePasswordExemption: Bool
  ) async throws -> RemoteNodeSessionDTO {
    let onTimeoutKnown: @Sendable (Int) async -> Void = { [weak self] seconds in
      await MainActor.run {
        guard let self, self.accepts(generation: generation, ticket: ticket) else { return }
        self.authTimeoutSeconds = seconds
        self.authStartTime = Date.now
        self.authSecondsRemaining = seconds
        self.startCountdownTask(generation: generation, ticket: ticket)
      }
    }

    guard accepts(generation: generation, ticket: ticket) else { throw CancellationError() }
    switch role {
    case .roomServer:
      guard let roomServerService else { throw RemoteNodeError.notConnected }
      return try await roomServerService.joinRoom(
        radioID: currentContact.radioID,
        contact: currentContact,
        password: password,
        rememberPassword: rememberPassword,
        pathLength: pathLength,
        samePasswordExemption: samePasswordExemption,
        onTimeoutKnown: onTimeoutKnown
      )
    case .sensor:
      guard let remoteNodeService else { throw RemoteNodeError.notConnected }
      return try await remoteNodeService.connectSensor(
        radioID: currentContact.radioID,
        contact: currentContact,
        password: password,
        rememberPassword: rememberPassword,
        requireAdmin: requireAdmin,
        pathLength: pathLength,
        samePasswordExemption: samePasswordExemption,
        onTimeoutKnown: onTimeoutKnown
      )
    case .repeater:
      guard let repeaterAdminService else { throw RemoteNodeError.notConnected }
      return try await repeaterAdminService.connectAsAdmin(
        radioID: currentContact.radioID,
        contact: currentContact,
        password: password,
        rememberPassword: rememberPassword,
        pathLength: pathLength,
        samePasswordExemption: samePasswordExemption,
        onTimeoutKnown: onTimeoutKnown
      )
    }
  }

  private func accepts(generation: UUID, ticket: Epoch.Ticket? = nil) -> Bool {
    !Task.isCancelled && connectionGeneration == generation && (ticket?.isCurrent(in: authEpoch) ?? true)
  }

  private func startCountdownTask(generation: UUID, ticket: Epoch.Ticket) {
    countdownTask = Task {
      while !Task.isCancelled, let timeout = authTimeoutSeconds, let startTime = authStartTime {
        do {
          try await Task.sleep(for: Self.countdownTickInterval)
        } catch {
          break
        }
        guard accepts(generation: generation, ticket: ticket) else { return }

        let elapsed = Date.now.timeIntervalSince(startTime)
        let remaining = max(0, timeout - Int(elapsed))
        authSecondsRemaining = remaining
        if remaining == 0 {
          break
        }
      }
    }
  }

  private func cleanupCountdownState() {
    countdownTask?.cancel()
    countdownTask = nil
    authSecondsRemaining = nil
    authStartTime = nil
    authTimeoutSeconds = nil
  }
}
