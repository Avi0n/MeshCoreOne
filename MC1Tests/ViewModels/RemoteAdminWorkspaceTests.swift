import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@MainActor
private final class ControllableCLISend {
  private var continuations: [CheckedContinuation<String, Error>] = []
  private(set) var sendCount = 0
  private(set) var commands: [String] = []

  func send(sessionID: UUID, command: String, timeout: Duration) async throws -> String {
    sendCount += 1
    commands.append(command)
    return try await withCheckedThrowingContinuation { continuations.append($0) }
  }

  func pendingCount() -> Int {
    continuations.count
  }

  func completeOldest(_ value: String) {
    guard !continuations.isEmpty else { return }
    continuations.removeFirst().resume(returning: value)
  }
}

@Suite("Remote admin workspaces")
@MainActor
struct RemoteAdminWorkspaceTests {
  private func makeSession(publicKeyByte: UInt8 = 0x42, radioID: UUID = UUID()) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      radioID: radioID,
      publicKey: Data(repeating: publicKeyByte, count: 32),
      name: "Node",
      role: .repeater,
      isConnected: true,
      permissionLevel: .admin
    )
  }

  private func bindSettings(_ viewModel: RepeaterSettingsViewModel, send: ControllableCLISend) {
    viewModel.helper.configure(
      session: makeSession(),
      sendCommand: { sessionID, command, timeout in
        try await send.send(sessionID: sessionID, command: command, timeout: timeout)
      },
      sendRawCommand: { sessionID, command, timeout in
        try await send.send(sessionID: sessionID, command: command, timeout: timeout)
      }
    )
    viewModel.helper.onRevertUncommittedSettingsEdits = { [weak viewModel] in
      viewModel?.revertUncommittedSettingsEdits()
    }
    viewModel.bindSettingsVisitReset()
  }

  private func bindRoomSettings(_ viewModel: RoomSettingsViewModel, send: ControllableCLISend) {
    viewModel.helper.configure(
      session: RemoteNodeSessionDTO(
        radioID: UUID(),
        publicKey: Data(repeating: 0x33, count: 32),
        name: "Room",
        role: .roomServer,
        isConnected: true,
        permissionLevel: .admin
      ),
      sendCommand: { sessionID, command, timeout in
        try await send.send(sessionID: sessionID, command: command, timeout: timeout)
      },
      sendRawCommand: { sessionID, command, timeout in
        try await send.send(sessionID: sessionID, command: command, timeout: timeout)
      }
    )
    viewModel.helper.onRevertUncommittedSettingsEdits = { [weak viewModel] in
      viewModel?.revertUncommittedSettingsEdits()
    }
    viewModel.helper.otherSettingsApplyInFlight = { [weak viewModel] in
      guard let viewModel else { return false }
      return viewModel.isApplyingRoomAccess || viewModel.isApplyingBehavior
    }
    viewModel.bindSettingsVisitReset()
  }

  @Test
  func `parent task cancellation does not abort a detached behavior fetch`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)

    let parent = Task {
      Task { await viewModel.fetchBehaviorSettings() }
    }
    parent.cancel()

    for reply in ["on", "15", "12", "3"] {
      try await waitUntil(timeout: .seconds(1), "behavior fetch should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest(reply)
    }

    try await waitUntil(timeout: .seconds(2), "behavior fields should apply") {
      viewModel.repeaterEnabled == true
    }
    #expect(viewModel.advertIntervalMinutes == 15)
  }

  @Test
  func `behavior fetch started before dismiss does not apply and a later fetch does`() async throws {
    let workspaces = RemoteAdminWorkspaces()
    let session = makeSession()
    let send = ControllableCLISend()
    let viewModel = workspaces.repeaterSettings(for: session)
    bindSettings(viewModel, send: send)

    let fetch = Task { await viewModel.fetchBehaviorSettings() }
    try await waitUntil(timeout: .seconds(1), "fetch should start") {
      send.sendCount >= 1
    }
    #expect(workspaces.repeaterSettings(for: session) === viewModel)
    viewModel.helper.noteSettingsDisappeared()

    for reply in ["on", "15", "12", "3"] {
      try await waitUntil(timeout: .seconds(1), "fetch should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest(reply)
    }
    await fetch.value

    #expect(viewModel.repeaterEnabled == nil)
    #expect(viewModel.advertIntervalMinutes == nil)

    viewModel.helper.noteSettingsAppeared()
    let next = Task { await viewModel.fetchBehaviorSettings() }
    for reply in ["on", "15", "12", "3"] {
      try await waitUntil(timeout: .seconds(1), "next fetch should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest(reply)
    }
    await next.value

    #expect(viewModel.repeaterEnabled == true)
    #expect(viewModel.advertIntervalMinutes == 15)
  }

  @Test
  func `dismiss during apply still records success`() async throws {
    let workspaces = RemoteAdminWorkspaces()
    let session = makeSession()
    let send = ControllableCLISend()
    let viewModel = workspaces.repeaterSettings(for: session)
    bindSettings(viewModel, send: send)

    viewModel.repeaterEnabled = true
    viewModel.advertIntervalMinutes = 60
    viewModel.floodAdvertIntervalHours = 12
    viewModel.floodMaxHops = 3

    let apply = Task { await viewModel.applyBehaviorSettings() }
    try await waitUntil(timeout: .seconds(1), "apply should start") {
      send.sendCount >= 1
    }

    #expect(workspaces.repeaterSettings(for: session) === viewModel)

    for _ in 0..<4 {
      try await waitUntil(timeout: .seconds(1), "apply command should be waiting") {
        send.pendingCount() >= 1
      }
      send.completeOldest("OK")
    }

    try await waitUntil(timeout: .seconds(2), "apply should flash success") {
      viewModel.behaviorApplySuccess
    }
    await apply.value

    #expect(viewModel.helper.errorMessage == nil)
  }

  @Test
  func `a second node does not show the first node's fields`() {
    let workspaces = RemoteAdminWorkspaces()
    let first = workspaces.repeaterSettings(for: makeSession(publicKeyByte: 0x11))
    first.advertIntervalMinutes = 20
    let second = workspaces.repeaterSettings(for: makeSession(publicKeyByte: 0x22))
    #expect(second !== first)
    #expect(second.advertIntervalMinutes == nil)
  }

  @Test
  func `reset drops the workspace without a timeout error`() {
    let workspaces = RemoteAdminWorkspaces()
    let session = makeSession()
    let first = workspaces.repeaterSettings(for: session)
    first.advertIntervalMinutes = 20
    first.helper.errorMessage = "timeout"

    workspaces.reset()
    let next = workspaces.repeaterSettings(for: session)
    #expect(next !== first)
    #expect(next.advertIntervalMinutes == nil)
    #expect(next.helper.errorMessage == nil)
  }

  @Test
  func `reset stops discovery and a waiting node command`() async throws {
    let workspaces = RemoteAdminWorkspaces()
    let session = makeSession()
    let settings = workspaces.repeaterSettings(for: session)
    let status = workspaces.repeaterStatus(for: session)
    status.startDiscoveryForTesting()
    let send = ControllableCLISend()
    let cli = workspaces.nodeCLI(for: session)
    cli.configure(sessionName: session.name) { command, timeout in
      try await send.send(sessionID: session.id, command: command, timeout: timeout)
    }
    cli.executeCommand("ver")
    try await waitUntil(timeout: .seconds(1), "node CLI should wait") {
      cli.isWaitingForResponse
    }

    workspaces.reset()

    #expect(status.isDiscovering == false)
    #expect(cli.isWaitingForResponse == false)
    #expect(workspaces.repeaterSettings(for: session) !== settings)
    send.completeOldest("")
  }

  @Test
  func `status reply after the telemetry visit ended does not set status and a later visit does`() async {
    let workspaces = RemoteAdminWorkspaces()
    let session = makeSession()
    let viewModel = workspaces.repeaterStatus(for: session)
    viewModel.helper.session = session
    let response = StatusResponse(
      publicKeyPrefix: session.publicKey.prefix(6),
      battery: 3850,
      txQueueLength: 0,
      noiseFloor: -120,
      lastRSSI: -87,
      packetsReceived: 1000,
      packetsSent: 500,
      airtime: 100,
      uptime: 3600,
      sentFlood: 0,
      sentDirect: 0,
      receivedFlood: 0,
      receivedDirect: 0,
      fullEvents: 0,
      lastSNR: 8.5,
      directDuplicates: 0,
      floodDuplicates: 0,
      rxAirtime: 100,
      receiveErrors: 0
    )
    let visit = viewModel.helper.captureTelemetryVisit()
    await viewModel.helper.handleStatusResponse(response, visit: visit)
    #expect(viewModel.helper.status?.batteryMillivolts == 3850)

    viewModel.noteTelemetryVisitDisappeared()
    await viewModel.helper.handleStatusResponse(response, visit: visit)
    #expect(workspaces.repeaterStatus(for: session) === viewModel)
    #expect(viewModel.helper.status == nil)

    viewModel.noteTelemetryVisitAppeared()
    let nextVisit = viewModel.helper.captureTelemetryVisit()
    await viewModel.helper.handleStatusResponse(response, visit: nextVisit)
    #expect(viewModel.helper.status?.batteryMillivolts == 3850)
  }

  @Test
  func `discovery stopped by the telemetry visit does not apply a later neighbor result`() async {
    let workspaces = RemoteAdminWorkspaces()
    let session = makeSession()
    let viewModel = workspaces.repeaterStatus(for: session)
    let visit = viewModel.helper.captureTelemetryVisit()
    viewModel.startDiscoveryForTesting()
    #expect(viewModel.isDiscovering)

    viewModel.noteTelemetryVisitDisappeared()
    #expect(workspaces.repeaterStatus(for: session) === viewModel)
    #expect(viewModel.isDiscovering == false)

    let response = NeighboursResponse(
      publicKeyPrefix: session.publicKey.prefix(6),
      tag: Data(),
      totalCount: 1,
      neighbours: [
        Neighbour(publicKeyPrefix: Data(repeating: 0x11, count: 6), secondsAgo: 4, snr: 6)
      ]
    )
    await viewModel.handleNeighboursResponse(response, visit: visit)
    #expect(viewModel.neighbors.isEmpty)
    #expect(viewModel.neighborsLoaded == false)

    viewModel.noteTelemetryVisitAppeared()
    viewModel.startDiscoveryForTesting()
    let next = viewModel.helper.captureTelemetryVisit()
    await viewModel.handleNeighboursResponse(response, visit: next)
    #expect(viewModel.isDiscovering)
    #expect(viewModel.neighbors.count == 1)
    #expect(viewModel.neighborsLoaded)
    viewModel.stopDiscovery()
  }

  @Test
  func `room behavior fetch started before dismiss does not apply and a later fetch does`() async throws {
    let workspaces = RemoteAdminWorkspaces()
    let session = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0x33, count: 32),
      name: "Room",
      role: .roomServer,
      isConnected: true,
      permissionLevel: .admin
    )
    let send = ControllableCLISend()
    let viewModel = workspaces.roomSettings(for: session)
    viewModel.helper.configure(
      session: session,
      sendCommand: { sessionID, command, timeout in
        try await send.send(sessionID: sessionID, command: command, timeout: timeout)
      },
      sendRawCommand: { sessionID, command, timeout in
        try await send.send(sessionID: sessionID, command: command, timeout: timeout)
      }
    )

    viewModel.bindSettingsVisitReset()
    let fetch = Task { await viewModel.fetchBehaviorSettings() }
    try await waitUntil(timeout: .seconds(1), "room fetch should start") {
      send.sendCount >= 1
    }
    #expect(workspaces.roomSettings(for: session) === viewModel)
    viewModel.helper.noteSettingsDisappeared()
    for reply in ["15", "12", "3"] {
      try await waitUntil(timeout: .seconds(1), "room fetch should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest(reply)
    }
    await fetch.value
    #expect(viewModel.advertIntervalMinutes == nil)

    viewModel.helper.noteSettingsAppeared()
    let next = Task { await viewModel.fetchBehaviorSettings() }
    for reply in ["15", "12", "3"] {
      try await waitUntil(timeout: .seconds(1), "next room fetch should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest(reply)
    }
    await next.value
    #expect(viewModel.advertIntervalMinutes == 15)
  }

  @Test
  func `repeater seedUnloadedName leaves a stored node name alone`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)

    viewModel.seedUnloadedName("Tower")

    #expect(viewModel.helper.name == "Alpha")
    #expect(viewModel.helper.originalName == "Alpha")
    #expect(viewModel.helper.identitySettingsModified == false)
  }

  @Test
  func `repeater seedUnloadedName leaves an unsaved edit alone`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.name = "Beta"

    viewModel.seedUnloadedName("Tower")

    #expect(viewModel.helper.name == "Beta")
    #expect(viewModel.helper.originalName == "Alpha")
    #expect(viewModel.helper.identitySettingsModified)
  }

  @Test
  func `repeater seedUnloadedName sets name only on first open`() {
    let viewModel = RepeaterSettingsViewModel()

    viewModel.seedUnloadedName("Tower")

    #expect(viewModel.helper.name == "Tower")
    #expect(viewModel.helper.originalName == nil)
    #expect(viewModel.helper.nameBaseline == "Tower")
    #expect(viewModel.helper.identitySettingsModified == false)
  }

  @Test
  func `room seedUnloadedIdentity leaves a stored name alone`() {
    let viewModel = RoomSettingsViewModel()
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Room-2", ownerInfo: nil)

    viewModel.seedUnloadedIdentity("Contact")

    #expect(viewModel.helper.name == "Room-2")
    #expect(viewModel.helper.originalName == "Room-2")
    #expect(viewModel.helper.identitySettingsModified == false)
  }

  @Test
  func `room seedUnloadedIdentity seeds name and originalName on first open`() {
    let viewModel = RoomSettingsViewModel()

    viewModel.seedUnloadedIdentity("Contact")

    #expect(viewModel.helper.name == "Contact")
    #expect(viewModel.helper.originalName == "Contact")
    #expect(viewModel.helper.identitySettingsModified == false)
  }

  @Test
  func `room refreshDeviceClockIfIdle sends clock when idle and skips while loading`() async throws {
    let send = ControllableCLISend()
    let viewModel = RoomSettingsViewModel()
    bindRoomSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Room-2", ownerInfo: nil)

    viewModel.refreshDeviceClockIfIdle()
    try await waitUntil(timeout: .seconds(1), "clock send should start") {
      send.sendCount >= 1
    }
    #expect(send.commands == ["clock"])
    #expect(viewModel.helper.isLoadingDeviceInfo)

    viewModel.refreshDeviceClockIfIdle()
    try await Task.sleep(for: .milliseconds(30))
    #expect(send.sendCount == 1)

    send.completeOldest("06:40 - 18/4/2025 UTC")
    try await waitUntil(timeout: .seconds(1), "clock fetch should finish") {
      viewModel.helper.isLoadingDeviceInfo == false
    }
    #expect(viewModel.helper.deviceTime != nil)
  }

  // MARK: - Uncommitted edits

  @Test
  func `repeater placeholder is not an identity edit`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.seedUnloadedName("Tower")
    #expect(viewModel.helper.identitySettingsModified == false)
    #expect(viewModel.hasUncommittedSettingsEdits == false)
  }

  @Test
  func `repeater name edit reverts to owner-info baseline`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.name = "Beta"
    #expect(viewModel.hasUncommittedSettingsEdits)
    viewModel.revertUncommittedSettingsEdits()
    #expect(viewModel.helper.name == "Alpha")
    #expect(viewModel.hasUncommittedSettingsEdits == false)
  }

  @Test
  func `repeater name edit before owner info reverts to placeholder`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.seedUnloadedName("Tower")
    viewModel.helper.name = "Beta"
    #expect(viewModel.hasUncommittedSettingsEdits)
    viewModel.revertUncommittedSettingsEdits()
    #expect(viewModel.helper.name == "Tower")
    #expect(viewModel.hasUncommittedSettingsEdits == false)
  }

  @Test
  func `room seed and name revert`() {
    let viewModel = RoomSettingsViewModel()
    viewModel.seedUnloadedIdentity("Contact")
    #expect(viewModel.hasUncommittedSettingsEdits == false)
    viewModel.helper.name = "Edited"
    #expect(viewModel.hasUncommittedSettingsEdits)
    viewModel.revertUncommittedSettingsEdits()
    #expect(viewModel.helper.name == "Contact")
  }

  @Test
  func `radio edit reverts to originals`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    viewModel.helper.frequency = 915
    #expect(viewModel.hasUncommittedSettingsEdits)
    viewModel.revertUncommittedSettingsEdits()
    #expect(viewModel.helper.frequency == 910)
    #expect(viewModel.hasUncommittedSettingsEdits == false)
  }

  @Test
  func `password fields count as uncommitted and clear on revert`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.newPassword = "secret"
    #expect(viewModel.hasUncommittedSettingsEdits)
    viewModel.revertUncommittedSettingsEdits()
    #expect(viewModel.helper.newPassword.isEmpty)
    #expect(viewModel.helper.confirmPassword.isEmpty)
  }

  @Test
  func `unsaved regions are not uncommitted field edits`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.hasUnsavedRegionChanges = true
    #expect(viewModel.hasUncommittedSettingsEdits == false)
    viewModel.revertUncommittedSettingsEdits()
    #expect(viewModel.hasUnsavedRegionChanges)
  }

  @Test
  func `placeholder apply does not send set name`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.seedUnloadedName("Tower")
    viewModel.helper.originalLatitude = 45
    viewModel.helper.latitude = 46

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set lat should start") {
      send.pendingCount() >= 1
    }
    #expect(send.commands.contains(where: { $0.hasPrefix("set name") }) == false)
    #expect(send.commands == ["set lat 46.0"] || send.commands.first == "set lat 46.0")
    send.completeOldest("OK")
    await apply.value
  }

  @Test
  func `radio apply keeps live edit typed during wait when sheet stays`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    viewModel.helper.frequency = 915

    let apply = Task { await viewModel.helper.applyRadioSettings() }
    try await waitUntil(timeout: .seconds(1), "set radio should start") {
      send.pendingCount() >= 1
    }
    #expect(send.commands.first?.contains("915") == true)
    viewModel.helper.frequency = 920
    send.completeOldest("OK - reboot to apply")
    await apply.value

    #expect(viewModel.helper.originalFrequency == 915)
    #expect(viewModel.helper.frequency == 920)
    #expect(viewModel.hasUncommittedSettingsEdits)
  }

  @Test
  func `radio apply reverts live edit when sheet stays gone`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    viewModel.helper.frequency = 915

    let apply = Task { await viewModel.helper.applyRadioSettings() }
    try await waitUntil(timeout: .seconds(1), "set radio should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.noteSettingsDisappeared()
    #expect(viewModel.helper.frequency == 915)
    viewModel.helper.frequency = 920
    send.completeOldest("OK - reboot to apply")
    await apply.value

    #expect(send.commands.first?.contains("915") == true)
    #expect(viewModel.helper.frequency == nil)
    #expect(viewModel.helper.originalFrequency == nil)
    #expect(viewModel.hasUncommittedSettingsEdits == false)
  }

  @Test
  func `radio apply come back cancels pending discard`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    viewModel.helper.frequency = 915

    let apply = Task { await viewModel.helper.applyRadioSettings() }
    try await waitUntil(timeout: .seconds(1), "set radio should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.noteSettingsDisappeared()
    viewModel.helper.noteSettingsAppeared()
    viewModel.helper.frequency = 920
    send.completeOldest("OK - reboot to apply")
    await apply.value

    #expect(viewModel.helper.frequency == 920)
    #expect(viewModel.helper.originalFrequency == 915)
  }

  @Test
  func `contact info apply with sheet gone keeps sent owner info`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.ownerInfo = "Ops"
    viewModel.helper.setNodeInfo(firmwareVersion: nil, name: nil, ownerInfo: "Old")
    viewModel.helper.ownerInfo = "Ops"

    let apply = Task { await viewModel.helper.applyContactInfoSettings() }
    try await waitUntil(timeout: .seconds(1), "set owner.info should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.noteSettingsDisappeared()
    send.completeOldest("OK")
    await apply.value

    #expect(send.commands.contains { $0.contains("Ops") })
    #expect(viewModel.helper.ownerInfo == nil)
    #expect(viewModel.helper.originalOwnerInfo == nil)
  }

  @Test
  func `identity apply snapshots latitude before first await`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.name = "Beta"
    viewModel.helper.originalLatitude = 45
    viewModel.helper.latitude = 46

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    #expect(send.commands.first == "set name Beta")
    viewModel.helper.latitude = 47
    send.completeOldest("OK")
    try await waitUntil(timeout: .seconds(1), "set lat should start") {
      send.pendingCount() >= 1
    }
    #expect(send.commands.contains("set lat 46.0"))
    send.completeOldest("OK")
    await apply.value

    #expect(viewModel.helper.originalLatitude == 46)
    #expect(viewModel.helper.latitude == 47)
  }

  @Test
  func `stale setNodeInfo during identity apply does not overwrite sent name`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.seedUnloadedName("Tower")
    viewModel.helper.name = "Beta"
    let loadTicket = viewModel.helper.beginSettingsLoad(fields: [.name, .ownerInfo])

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.setNodeInfo(
      firmwareVersion: "1.9",
      name: "Alpha",
      ownerInfo: "Owner",
      loadTicket: loadTicket
    )
    send.completeOldest("OK")
    await apply.value

    #expect(viewModel.helper.name == "Beta")
    #expect(viewModel.helper.nameBaseline == "Beta")
    #expect(viewModel.helper.originalName == "Beta")
    #expect(viewModel.helper.ownerInfo == "Owner")
    #expect(viewModel.helper.firmwareVersion == "1.9")

    let second = Task { await viewModel.helper.applyIdentitySettings() }
    try await Task.sleep(for: .milliseconds(50))
    #expect(send.commands.filter { $0.hasPrefix("set name") }.count == 1)
    second.cancel()
  }

  @Test
  func `stale setNodeInfo after identity apply still skips name`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.seedUnloadedName("Tower")
    viewModel.helper.name = "Beta"
    let loadTicket = viewModel.helper.beginSettingsLoad(fields: [.name, .ownerInfo])

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("OK")
    await apply.value

    viewModel.helper.setNodeInfo(
      firmwareVersion: "1.9",
      name: "Alpha",
      ownerInfo: "Owner",
      loadTicket: loadTicket
    )
    #expect(viewModel.helper.name == "Beta")
    #expect(viewModel.helper.ownerInfo == "Owner")
  }

  @Test
  func `stale get name during identity apply is ignored`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.seedUnloadedName("Tower")
    viewModel.helper.name = "Beta"

    let fetch = Task { await viewModel.helper.fetchIdentity() }
    try await waitUntil(timeout: .seconds(1), "get name should start") {
      send.commands.contains("get name")
    }
    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.commands.contains("set name Beta")
    }
    // Complete get name (oldest) then set name.
    send.completeOldest("Alpha")
    send.completeOldest("OK")
    // Finish remaining get lat / get lon from fetchIdentity.
    for _ in 0..<2 {
      try await waitUntil(timeout: .seconds(1), "identity fetch should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest("> 0")
    }
    await fetch.value
    await apply.value

    #expect(viewModel.helper.name == "Beta")
    #expect(viewModel.helper.nameBaseline == "Beta")
    #expect(viewModel.helper.originalName == "Beta")
  }

  @Test
  func `stale get lat during identity apply is ignored`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.latitude = 45
    viewModel.helper.originalLatitude = 45

    let fetch = Task { await viewModel.helper.fetchIdentity() }
    try await waitUntil(timeout: .seconds(1), "get lat should start") {
      send.commands.contains("get lat")
    }
    viewModel.helper.latitude = 46
    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set lat should start") {
      send.commands.contains(where: { $0.hasPrefix("set lat") })
    }
    send.completeOldest("45") // stale get lat
    send.completeOldest("OK") // set lat
    try await waitUntil(timeout: .seconds(1), "get lon should wait") {
      send.pendingCount() >= 1
    }
    send.completeOldest("10")
    await fetch.value
    await apply.value

    #expect(viewModel.helper.latitude == 46)
    #expect(viewModel.helper.originalLatitude == 46)
    let before = send.commands.filter { $0.hasPrefix("set lat") }.count
    let second = Task { await viewModel.helper.applyIdentitySettings() }
    try await Task.sleep(for: .milliseconds(50))
    #expect(send.commands.filter { $0.hasPrefix("set lat") }.count == before)
    second.cancel()
  }

  @Test
  func `load after identity apply writes name`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.seedUnloadedName("Tower")
    viewModel.helper.name = "Beta"

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("OK")
    await apply.value

    viewModel.helper.setNodeInfo(firmwareVersion: "2.0", name: "Gamma", ownerInfo: nil)
    #expect(viewModel.helper.name == "Gamma")
    #expect(viewModel.helper.nameBaseline == "Gamma")
    #expect(viewModel.helper.originalName == "Gamma")
  }

  @Test
  func `placeholder reseed during identity apply is ignored`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.seedUnloadedName("Tower")
    viewModel.helper.name = "Beta"

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    viewModel.seedUnloadedName("Contact")
    #expect(viewModel.helper.name == "Beta")
    send.completeOldest("OK")
    await apply.value

    #expect(viewModel.helper.name == "Beta")
    #expect(viewModel.helper.originalName == "Beta")
  }

  @Test
  func `identity apply with sheet gone still sends snapshotted lat`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.name = "Beta"
    viewModel.helper.originalLatitude = 45
    viewModel.helper.latitude = 46

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.noteSettingsDisappeared()
    send.completeOldest("OK")
    try await waitUntil(timeout: .seconds(1), "set lat should start") {
      send.pendingCount() >= 1
    }
    #expect(send.commands.contains("set lat 46.0"))
    send.completeOldest("OK")
    await apply.value

    #expect(viewModel.helper.name == nil)
    #expect(viewModel.helper.nameBaseline == nil)
  }

  @Test
  func `identity apply failure with sheet gone restores pre-apply name`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.name = "Beta"

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.noteSettingsDisappeared()
    send.completeOldest("ERR")
    await apply.value

    #expect(viewModel.helper.name == nil)
    #expect(viewModel.helper.errorMessage != nil)
  }

  @Test
  func `identity apply failure with sheet stays keeps edited name`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.name = "Beta"

    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("ERR")
    await apply.value

    #expect(viewModel.helper.name == "Beta")
  }

  @Test
  func `repeater behavior apply with sheet gone sends snapshotted second command`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.repeaterEnabled = true
    viewModel.advertIntervalMinutes = 60
    viewModel.floodAdvertIntervalHours = 12
    viewModel.floodMaxHops = 3

    let apply = Task { await viewModel.applyBehaviorSettings() }
    try await waitUntil(timeout: .seconds(1), "first behavior set should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.noteSettingsDisappeared()
    viewModel.floodAdvertIntervalHours = 24
    for _ in 0..<4 {
      try await waitUntil(timeout: .seconds(1), "behavior command should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest("OK")
    }
    await apply.value

    #expect(send.commands.contains("set flood.advert.interval 12"))
    #expect(viewModel.advertIntervalMinutes == nil)
  }

  @Test
  func `overlap keeps password until every apply is idle`() async throws {
    let send = ControllableCLISend()
    let viewModel = RoomSettingsViewModel()
    bindRoomSettings(viewModel, send: send)
    viewModel.helper.otherSettingsApplyInFlight = { viewModel.isApplyingBehavior }
    viewModel.helper.newPassword = "secret"
    viewModel.isApplyingBehavior = true
    viewModel.helper.noteSettingsDisappeared()
    #expect(viewModel.helper.newPassword == "secret")

    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Room", ownerInfo: nil)
    viewModel.helper.name = "Edited"
    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set name should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("OK")
    await apply.value
    #expect(viewModel.helper.newPassword == "secret")

    viewModel.isApplyingBehavior = false
    viewModel.helper.revertAbandonedDraftIfIdle()
    #expect(viewModel.helper.newPassword.isEmpty)
  }

  @Test
  func `late get radio without set adopts values`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.configure(
      session: makeSession(),
      sendCommand: { _, _, _ in "OK" },
      sendRawCommand: { _, _, _ in "OK" }
    )
    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    _ = viewModel.helper.beginSettingsLoad(query: "get radio", fields: [.radio])
    viewModel.helper.markUnansweredQueryForTesting("get radio")
    viewModel.helper.handleCommonLateResponse("> 915.000,250.0,10,5")

    #expect(viewModel.helper.frequency == 915)
    #expect(viewModel.helper.originalFrequency == 915)
    #expect(viewModel.hasUncommittedSettingsEdits == false)
    viewModel.revertUncommittedSettingsEdits()
    #expect(viewModel.helper.frequency == 915)
  }

  @Test
  func `late get radio after set does not overwrite applied values`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)

    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    _ = viewModel.helper.beginSettingsLoad(query: "get radio", fields: [.radio])
    viewModel.helper.markUnansweredQueryForTesting("get radio")

    viewModel.helper.frequency = 915
    let apply = Task { await viewModel.helper.applyRadioSettings() }
    try await waitUntil(timeout: .seconds(1), "set radio should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("OK - reboot to apply")
    await apply.value

    viewModel.helper.handleCommonLateResponse("> 910.000,250.0,10,5")

    #expect(viewModel.helper.frequency == 915)
    #expect(viewModel.helper.originalFrequency == 915)
    #expect(viewModel.hasUncommittedSettingsEdits == false)

    let before = send.commands.filter { $0.hasPrefix("set radio") }.count
    let second = Task { await viewModel.helper.applyRadioSettings() }
    try await Task.sleep(for: .milliseconds(50))
    #expect(send.commands.filter { $0.hasPrefix("set radio") }.count == before)
    second.cancel()
  }

  @Test
  func `stale get lon during identity apply is ignored`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.latitude = 45
    viewModel.helper.originalLatitude = 45
    viewModel.helper.longitude = 10
    viewModel.helper.originalLongitude = 10

    let fetch = Task { await viewModel.helper.fetchIdentity() }
    try await waitUntil(timeout: .seconds(1), "get lat should start") {
      send.commands.contains("get lat")
    }
    send.completeOldest("45")
    try await waitUntil(timeout: .seconds(1), "get lon should start") {
      send.commands.contains("get lon")
    }
    viewModel.helper.longitude = 11
    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set lon should start") {
      send.commands.contains(where: { $0.hasPrefix("set lon") })
    }
    send.completeOldest("10")
    send.completeOldest("OK")
    await fetch.value
    await apply.value

    #expect(viewModel.helper.longitude == 11)
    #expect(viewModel.helper.originalLongitude == 11)
  }

  @Test
  func `stale behavior get during repeater apply is ignored`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.repeaterEnabled = true
    viewModel.advertIntervalMinutes = 60
    viewModel.floodAdvertIntervalHours = 12
    viewModel.floodMaxHops = 3

    _ = viewModel.helper.beginSettingsLoad(query: "get advert.interval", fields: [.advertInterval])
    viewModel.helper.markUnansweredQueryForTesting("get advert.interval")
    viewModel.advertIntervalMinutes = 90

    let apply = Task { await viewModel.applyBehaviorSettings() }
    try await waitUntil(timeout: .seconds(1), "set repeat should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("OK")
    try await waitUntil(timeout: .seconds(1), "set advert.interval should start") {
      send.commands.contains(where: { $0.hasPrefix("set advert.interval") })
    }
    viewModel.helper.handleCommonLateResponse("60")
    #expect(viewModel.advertIntervalMinutes == 90)

    for _ in 0..<3 {
      try await waitUntil(timeout: .seconds(1), "behavior set should wait") {
        send.pendingCount() >= 1
      }
      send.completeOldest("OK")
    }
    await apply.value

    #expect(send.commands.contains("set advert.interval 90"))
    #expect(viewModel.advertIntervalMinutes == 90)
  }

  @Test
  func `stale owner info during contact apply keeps sent value`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.0", name: "Alpha", ownerInfo: "Old")
    viewModel.helper.ownerInfo = "Sent"
    let loadTicket = viewModel.helper.beginSettingsLoad(fields: [.name, .ownerInfo])

    let apply = Task { await viewModel.helper.applyContactInfoSettings() }
    try await waitUntil(timeout: .seconds(1), "set owner.info should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.setNodeInfo(
      firmwareVersion: "1.1",
      name: "Other",
      ownerInfo: "Stale",
      loadTicket: loadTicket
    )
    send.completeOldest("OK")
    await apply.value

    #expect(viewModel.helper.ownerInfo == "Sent")
    #expect(viewModel.helper.originalOwnerInfo == "Sent")
    #expect(viewModel.helper.firmwareVersion == "1.1")
  }

  @Test
  func `room access apply with sheet gone sends snapshotted password`() async throws {
    let send = ControllableCLISend()
    let viewModel = RoomSettingsViewModel()
    bindRoomSettings(viewModel, send: send)
    viewModel.guestPassword = "guest"
    viewModel.allowReadOnly = true

    let apply = Task { await viewModel.applyRoomAccess() }
    try await waitUntil(timeout: .seconds(1), "set guest.password should start") {
      send.pendingCount() >= 1
    }
    viewModel.helper.noteSettingsDisappeared()
    viewModel.guestPassword = "changed"
    send.completeOldest("OK")
    try await waitUntil(timeout: .seconds(1), "set allow.read.only should start") {
      send.pendingCount() >= 1
    }
    #expect(send.commands.contains("set guest.password guest"))
    send.completeOldest("OK")
    await apply.value

    #expect(viewModel.guestPassword == nil)
  }

  @Test
  func `late get lat after set lat does not overwrite`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.2", name: "Alpha", ownerInfo: nil)
    viewModel.helper.latitude = 45
    viewModel.helper.originalLatitude = 45
    _ = viewModel.helper.beginSettingsLoad(query: "get lat", fields: [.latitude])
    viewModel.helper.markUnansweredQueryForTesting("get lat")

    viewModel.helper.latitude = 46
    let apply = Task { await viewModel.helper.applyIdentitySettings() }
    try await waitUntil(timeout: .seconds(1), "set lat should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("OK")
    await apply.value

    viewModel.helper.handleCommonLateResponse("45")
    #expect(viewModel.helper.latitude == 46)
    #expect(viewModel.helper.originalLatitude == 46)
  }

  @Test
  func `behavior field errors clear on revert`() {
    let repeater = RepeaterSettingsViewModel()
    repeater.advertIntervalError = "bad"
    repeater.floodAdvertIntervalError = "bad"
    repeater.floodMaxHopsError = "bad"
    repeater.revertUncommittedSettingsEdits()
    #expect(repeater.advertIntervalError == nil)
    #expect(repeater.floodAdvertIntervalError == nil)
    #expect(repeater.floodMaxHopsError == nil)

    let room = RoomSettingsViewModel()
    room.advertIntervalError = "bad"
    room.floodAdvertIntervalError = "bad"
    room.floodMaxHopsError = "bad"
    room.revertUncommittedSettingsEdits()
    #expect(room.advertIntervalError == nil)
    #expect(room.floodAdvertIntervalError == nil)
    #expect(room.floodMaxHopsError == nil)
  }

  @Test
  func `region default clears unsaved region changes`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.setNodeInfo(firmwareVersion: "1.15.0", name: "Alpha", ownerInfo: nil)
    viewModel.originalRegions = [
      RepeaterRegionEntry(name: "Home", parentName: "*", depth: 1, floodAllowed: true, isHome: true)
    ]
    viewModel.regions = viewModel.originalRegions ?? []
    viewModel.hasUnsavedRegionChanges = true
    viewModel.defaultScopeLoaded = true

    let setDefault = Task { await viewModel.setDefaultScope(name: "Home") }
    try await waitUntil(timeout: .seconds(1), "region default should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("default scope is now Home")
    await setDefault.value

    #expect(viewModel.hasUnsavedRegionChanges == false)
  }

  @Test
  func `region alert saving does not dismiss`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    state.tapDontSave()
    state.tapCancelRegion()
    #expect(state.showRegionAlert)
    #expect(state.didDismissSheet == false)
    #expect(state.regionAlertPhase == .saving)
  }

  @Test
  func `region alert success cancel leaves sheet up`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    state.noteRegionsPersisted()
    #expect(state.beginRegionSave() == false)
    #expect(state.regionAlertPhase == .succeeded)
    state.tapDontSave()
    #expect(state.showRegionAlert)
    #expect(state.didDismissSheet == false)
    state.tapCancelRegion()
    #expect(state.showRegionAlert)
    #expect(state.didDismissSheet == false)
    let dismissed = state.finishRegionSave(errorMessage: nil, hasUnsavedRegionChanges: false)
    #expect(dismissed)
    #expect(state.didDismissSheet)
  }

  @Test
  func `region alert message follows the phase`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    #expect(state.regionAlertMessage(errorMessage: nil) == L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsMessage)
    state.beginRegionSave()
    #expect(state.regionAlertMessage(errorMessage: nil) == L10n.RemoteNodes.RemoteNodes.Settings.savingRegions)
    state.noteRegionsPersisted()
    #expect(state.regionAlertMessage(errorMessage: "ignored") == L10n.RemoteNodes.RemoteNodes.Settings.savingRegions)
    state.regionAlertPhase = .failed
    #expect(state.regionAlertMessage(errorMessage: "failed") == "failed")
    #expect(state.regionAlertMessage(errorMessage: nil) == L10n.RemoteNodes.RemoteNodes.Settings.unsavedRegionsMessage)
  }

  @Test
  func `region alert success with no tap dismisses sheet`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    state.noteRegionsPersisted()
    let dismissed = state.finishRegionSave(errorMessage: nil, hasUnsavedRegionChanges: false)
    #expect(dismissed)
    #expect(state.showRegionAlert == false)
    #expect(state.didDismissSheet)
  }

  @Test
  func `region alert failure stays up`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    let dismissed = state.finishRegionSave(errorMessage: "failed", hasUnsavedRegionChanges: true)
    #expect(dismissed == false)
    #expect(state.regionAlertPhase == .failed)
    #expect(state.showRegionAlert)
    #expect(state.didDismissSheet == false)
  }

  @Test
  func `region alert failure after persist note stays up`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    state.noteRegionsPersisted()
    let dismissed = state.finishRegionSave(errorMessage: "failed", hasUnsavedRegionChanges: false)
    #expect(dismissed == false)
    #expect(state.regionAlertPhase == .failed)
    #expect(state.showRegionAlert)
    #expect(state.didDismissSheet == false)
  }

  @Test
  func `region alert finish observes cleared flag without persist note`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    let dismissed = state.finishRegionSave(errorMessage: nil, hasUnsavedRegionChanges: false)
    #expect(dismissed)
    #expect(state.regionAlertPhase == .succeeded)
    #expect(state.showRegionAlert == false)
    #expect(state.didDismissSheet)
  }

  @Test
  func `region alert finish with unsaved changes returns to unsaved`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    let dismissed = state.finishRegionSave(errorMessage: nil, hasUnsavedRegionChanges: true)
    #expect(dismissed == false)
    #expect(state.regionAlertPhase == .unsaved)
    #expect(state.showRegionAlert)
    #expect(state.didDismissSheet == false)
  }

  @Test
  func `region alert failed then dont save dismisses`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.beginRegionSave()
    _ = state.finishRegionSave(errorMessage: "failed", hasUnsavedRegionChanges: true)
    state.tapDontSave()
    #expect(state.didDismissSheet)
    #expect(state.showRegionAlert == false)
  }

  @Test
  func `region alert unsaved dont save dismisses`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.tapDontSave()
    #expect(state.didDismissSheet)
    #expect(state.showRegionAlert == false)
  }

  @Test
  func `region alert unsaved cancel keeps sheet`() {
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.tapCancelRegion()
    #expect(state.showRegionAlert == false)
    #expect(state.didDismissSheet == false)
  }

  @Test
  func `idle disappear collapses radio and the next load can write`() async throws {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    viewModel.helper.isRadioExpanded = true
    viewModel.helper.isDeviceInfoExpanded = true
    viewModel.isBehaviorExpanded = true
    viewModel.isRegionsExpanded = true

    viewModel.helper.noteSettingsDisappeared()

    #expect(viewModel.helper.isRadioExpanded == false)
    #expect(viewModel.helper.isDeviceInfoExpanded == false)
    #expect(viewModel.isBehaviorExpanded == false)
    #expect(viewModel.isRegionsExpanded == false)
    #expect(viewModel.helper.frequency == nil)
    #expect(viewModel.helper.originalFrequency == nil)

    viewModel.helper.noteSettingsAppeared()
    let fetch = Task { await viewModel.helper.fetchRadioSettings() }
    try await waitUntil(timeout: .seconds(1), "get radio should start") {
      send.pendingCount() >= 1
    }
    send.completeOldest("915.000,250.0,10,5")
    await fetch.value

    #expect(viewModel.helper.frequency == 915)
    #expect(viewModel.helper.originalFrequency == 915)
  }

  @Test
  func `late get radio from the previous visit does not write`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.helper.configure(
      session: makeSession(),
      sendCommand: { _, _, _ in "OK" },
      sendRawCommand: { _, _, _ in "OK" }
    )
    viewModel.helper.adoptRadioValues(frequency: 910, bandwidth: 250, spreadingFactor: 10, codingRate: 5)
    _ = viewModel.helper.beginSettingsLoad(query: "get radio", fields: [.radio])
    viewModel.helper.markUnansweredQueryForTesting("get radio")
    viewModel.helper.noteSettingsDisappeared()

    viewModel.helper.handleCommonLateResponse("> 910.000,250.0,10,5")
    #expect(viewModel.helper.frequency == nil)

    viewModel.helper.noteSettingsAppeared()
    _ = viewModel.helper.beginSettingsLoad(query: "get radio", fields: [.radio])
    viewModel.helper.markUnansweredQueryForTesting("get radio")
    viewModel.helper.handleCommonLateResponse("> 915.000,250.0,10,5")
    #expect(viewModel.helper.frequency == 915)
  }

  @Test
  func `unsaved regions survive visit end until the flag is cleared`() {
    let viewModel = RepeaterSettingsViewModel()
    viewModel.bindSettingsVisitReset()
    let entry = RepeaterRegionEntry(name: "Home", parentName: "*", depth: 1, floodAllowed: true, isHome: true)
    viewModel.regions = [entry]
    viewModel.originalRegions = [entry]
    viewModel.defaultScopeName = "Home"
    viewModel.defaultScopeLoaded = true
    viewModel.hasUnsavedRegionChanges = true

    viewModel.helper.noteSettingsDisappeared()
    #expect(viewModel.hasUnsavedRegionChanges)
    #expect(viewModel.regions.count == 1)
    #expect(viewModel.originalRegions != nil)
    #expect(viewModel.defaultScopeName == "Home")
    #expect(viewModel.defaultScopeLoaded)

    viewModel.hasUnsavedRegionChanges = false
    viewModel.helper.noteSettingsDisappeared()
    #expect(viewModel.regions.isEmpty)
    #expect(viewModel.originalRegions == nil)
    #expect(viewModel.defaultScopeName == nil)
    #expect(viewModel.defaultScopeLoaded == false)
  }

  @Test
  func `region alert dont save clears the flag and does not send region save`() {
    let send = ControllableCLISend()
    let viewModel = RepeaterSettingsViewModel()
    bindSettings(viewModel, send: send)
    viewModel.hasUnsavedRegionChanges = true
    let state = SettingsExitGuardState()
    state.presentRegionAlert()
    state.tapDontSave {
      viewModel.hasUnsavedRegionChanges = false
    }
    #expect(viewModel.hasUnsavedRegionChanges == false)
    #expect(state.didDismissSheet)
    #expect(state.showRegionAlert == false)
    #expect(send.commands.contains("region save") == false)
  }

  @Test
  func `ending the telemetry visit collapses telemetry`() {
    let viewModel = RepeaterStatusViewModel()
    viewModel.helper.telemetryLoaded = true
    viewModel.helper.telemetryExpanded = true
    viewModel.helper.statusExpanded = true
    viewModel.helper.isBatteryCurveExpanded = true
    viewModel.neighborsExpanded = true
    viewModel.ownerInfoExpanded = true
    viewModel.ownerInfo = "Ops"
    let ocv = viewModel.helper.ocvValues

    viewModel.noteTelemetryVisitDisappeared()

    #expect(viewModel.helper.telemetryExpanded == false)
    #expect(viewModel.helper.telemetryLoaded == false)
    #expect(viewModel.helper.statusExpanded == false)
    #expect(viewModel.helper.isBatteryCurveExpanded == false)
    #expect(viewModel.neighborsExpanded == false)
    #expect(viewModel.ownerInfoExpanded == false)
    #expect(viewModel.ownerInfo == nil)
    #expect(viewModel.helper.ocvValues == ocv)
  }

  @Test
  func `settings visit end leaves CLI input`() {
    let workspaces = RemoteAdminWorkspaces()
    let session = makeSession()
    let cli = workspaces.nodeCLI(for: session)
    cli.currentInput = "get radio"
    let settings = workspaces.repeaterSettings(for: session)
    settings.helper.noteSettingsDisappeared()
    #expect(workspaces.nodeCLI(for: session) === cli)
    #expect(cli.currentInput == "get radio")
  }
}
