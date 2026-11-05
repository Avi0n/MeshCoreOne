import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import OSLog
import SwiftUI
import Testing
import UIKit
import Vision

@Suite("Node authentication external native source smoke", .serialized)
@MainActor
struct NodeAuthenticationSourcePresentationTests {
  @Test(.enabled(if: UserDefaults.standard.bool(forKey: Smoke.preferenceKey), "Requires external native interaction"),
        arguments: Mode.allCases)
  func `retained authentication uses only its admitted source`(mode: Mode) async throws {
    let env = try await ConnectionLinkSwitchHarness.make(live: .wifi, wifiHost: Smoke.host)
    guard let original = env.manager.currentConnection else { throw FixtureError.noSource }
    let vault = original.services.remoteNodeService
    let key = Data(UUID().uuidString.utf8.prefix(ProtocolLimits.publicKeySize))
    let ghost = env.device.copy {
      $0.id = UUID(); $0.radioID = UUID(); $0.publicKey = Self.canonicalKey
      $0.isActive = false; $0.connectionMethods = []
    }
    var host: SettingsTabHarness?
    var caught: (any Error)?
    do {
      await env.bluetooth.setConnectError(nil)
      await env.wifi.setConnectError(nil)
      try await env.manager.persistenceStore.saveDevice(ghost)
      if mode == .current { try await adoptGhost(in: env, expectedRadioID: ghost.radioID) }
      let contact = makeContact(radioID: mode == .current ? ghost.radioID : original.device.radioID, key: key)
      guard let setupSource = env.manager.currentConnection else { throw FixtureError.noSource }
      try await setupSource.services.contactService.addOrUpdateContact(
        radioID: contact.radioID, contact: contact.toContactFrame()
      )
      try await vault.storePassword(Smoke.password, forNodeKey: key)
      let harness = try SettingsTabHarness.make(connected: false, injectedAppState: env.appState)
      host = harness
      var prepared = false
      var successes = 0
      let sheet = NodeAuthenticationSheet(contact: contact, role: .repeater) { _ in successes += 1 }
        .environment(\.appState, env.appState)
      let root = VStack(spacing: 0) {
        sheet
        Button("Fixture prepared") { prepared = true }.accessibilityIdentifier("auth-source-fixture-prepared")
      }
      harness.window.rootViewController = UIHostingController(rootView: root)
      harness.window.makeKeyAndVisible()
      logger.notice("MC1NodeAuthUISmoke prepare mode=\(mode.rawValue, privacy: .public): flood on, remember off, then Fixture prepared")
      try await waitUntil { prepared }
      if mode == .foreign {
        try await adoptGhost(in: env, expectedRadioID: ghost.radioID)
        try await env.manager.persistenceStore.saveContact(makeContact(radioID: ghost.radioID, key: key))
      }
      if mode.isHeld { await env.wifi.holdLoginResults() }
      let start = await env.wifi.sentData.count
      logger.notice("MC1NodeAuthUISmoke connect-ready mode=\(mode.rawValue, privacy: .public): tap actual Connect once")
      if mode.isHeld {
        try await waitUntil { await env.wifi.heldLoginResultCount > 0 }
        let originalLoginEntered = await vault.pendingLoginCount == 1
        try #require(originalLoginEntered)
        #expect(successes == 0)
        logger.notice("MC1NodeAuthUISmoke admitted mode=\(mode.rawValue, privacy: .public)")
        if mode == .heldSamePartition {
          _ = try await env.manager.connectViaWiFi(host: Smoke.host, port: ConnectionMethod.defaultWiFiPort)
        } else { try await adoptGhost(in: env, expectedRadioID: ghost.radioID) }
        let live = env.manager.currentConnection
        let replacedGeneration = live.map { $0.generation != original.generation } ?? false
        let expectedPartition = live?.device.radioID == (mode == .heldSamePartition ? original.device.radioID : ghost.radioID)
        #expect(replacedGeneration)
        #expect(expectedPartition)
        let pendingWasRetired = await vault.pendingLoginCount == 0
        #expect(pendingWasRetired)
        let replacementStart = await env.wifi.sentData.count
        await env.wifi.releaseLoginResults()
        let settledCountdown = countdown(in: harness.window)
        try await Task.sleep(for: Smoke.countdownProbe)
        let laterCountdown = countdown(in: harness.window)
        let noLateCountdownUpdate = laterCountdown == settledCountdown
        #expect(noLateCountdownUpdate)
        let staleErrorVisible = visible(RemoteNodeError.cancelled.userFacingMessage, on: harness)
          || visible(L10n.RemoteNodes.RemoteNodes.Status.requestTimedOut, on: harness)
        #expect(staleErrorVisible == false)
        #expect(successes == 0)
        let replacementFrames = await env.wifi.sentData.dropFirst(replacementStart)
        let noReplacementAuth = !replacementFrames.contains { Self.authCodes.contains($0.first ?? 0xFF) }
        #expect(noReplacementAuth)
      } else {
        try await waitUntil {
          successes > 0 || visible(RemoteNodeError.notConnected.userFacingMessage, on: harness)
        }
        let frames = await env.wifi.sentData.dropFirst(start)
        let resets = frames.filter { $0.first == CommandCode.resetPath.rawValue }.count
        let logins = frames.filter { $0.first == CommandCode.sendLogin.rawValue }.count
        #expect(resets == (mode == .current ? 1 : 0))
        #expect(logins == (mode == .current ? 1 : 0))
        #expect(successes == (mode == .current ? 1 : 0))
        let savedContact = try await env.manager.persistenceStore.fetchContact(radioID: ghost.radioID, publicKey: key)
        let pathMatchesExpected = savedContact.map { $0.isFloodRouted == (mode == .current) } ?? false
        #expect(pathMatchesExpected)
      }
      let admittedContactSurvived = try await env.manager.persistenceStore.fetchContact(
        radioID: contact.radioID, publicKey: key
      ) != nil
      #expect(admittedContactSurvived)
      let passwordPreserved = await vault.retrievePassword(forContact: contact) == Smoke.password
      #expect(passwordPreserved == (mode != .current))
      let sessions = try await env.manager.persistenceStore.fetchRemoteNodeSessions(radioID: ghost.radioID)
      let connectedCount = sessions.filter { $0.publicKey == key && $0.isConnected }.count
      #expect(connectedCount == (mode == .current ? 1 : 0))
      let time = try await env.manager.session?.getTime()
      let laterIOWorks = (time?.timeIntervalSince1970 ?? 0) > 0
      #expect(laterIOWorks)
      logger.notice("MC1NodeAuthUISmoke settled mode=\(mode.rawValue, privacy: .public) successCount=\(successes, privacy: .public)")
    } catch { caught = error }
    host?.tearDown()
    await env.wifi.releaseAllGates()
    await env.bluetooth.releaseAllGates()
    try? await vault.deletePassword(forContact: makeContact(radioID: original.device.radioID, key: key))
    await env.close()
    if let caught { throw caught }
  }

  private func adoptGhost(in env: ConnectionLinkSwitchHarness, expectedRadioID: UUID) async throws {
    guard let source = env.manager.currentConnection else { throw FixtureError.noSource }
    await env.wifi.setIdentityImportPublicKey(Self.canonicalKey)
    try await source.services.nodeConfigService.importConfig(
      MeshCoreNodeConfig(privateKey: Self.privateKey), sections: ConfigSections(nodeIdentity: true), radioID: source.device.radioID
    )
    let adoptedCanonicalPartition = env.manager.currentConnection?.device.radioID == expectedRadioID
    try #require(adoptedCanonicalPartition)
  }

  private func visible(_ text: String, on host: SettingsTabHarness) -> Bool {
    host.labels(in: host.window).contains { $0.contains(text) } || !host.windowRects(matching: text).isEmpty
  }

  private func countdown(in window: UIWindow) -> String? {
    let image = UIGraphicsImageRenderer(bounds: window.bounds).image { window.layer.render(in: $0.cgContext) }
    guard let pixels = image.cgImage else { return nil }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    try? VNImageRequestHandler(cgImage: pixels, options: [:]).perform([request])
    let words = request.results?.compactMap { $0.topCandidates(1).first?.string } ?? []
    return (1...Smoke.maximumCountdown).map { L10n.RemoteNodes.RemoteNodes.Auth.secondsRemaining($0) }
      .first { words.contains($0) }
  }

  private func makeContact(radioID: UUID, key: Data) -> ContactDTO {
    ContactDTO(
      id: UUID(), radioID: radioID, publicKey: key, name: "Native retained auth", typeRawValue: ContactType.repeater.rawValue,
      flags: 0, outPathLength: 0, outPath: Data(), lastAdvertTimestamp: 0, latitude: 0, longitude: 0,
      lastModified: 0, lastHeardTimestamp: nil, nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
      lastMessageDate: nil, unreadCount: 0
    )
  }

  enum Mode: String, CaseIterable, Sendable {
    case foreign, current, heldForeign, heldSamePartition
    var isHeld: Bool {
      self == .heldForeign || self == .heldSamePartition
    }
  }

  private enum FixtureError: Error { case noSource, interactionTimedOut }
  private enum Smoke {
    nonisolated static let preferenceKey = "MC1NodeAuthUISmoke"
    static let host = "source.local"
    static let password = "synthetic-auth"
    static let interactionTimeout: Duration = .seconds(120)
    static let poll: Duration = .milliseconds(100)
    static let countdownProbe: Duration = .milliseconds(1100)
    static let maximumCountdown = 120
  }

  private let logger = Logger(subsystem: "com.mc1.tests", category: "NodeAuthUISmoke")
  private static let authCodes = [CommandCode.resetPath.rawValue, CommandCode.sendLogin.rawValue]
  private static let canonicalKey = Data([
    0x1E, 0xC7, 0x71, 0x75, 0xB0, 0x91, 0x8E, 0xD2, 0x06, 0xF9, 0xAE, 0x04, 0xEC, 0x13, 0x6D, 0x6D,
    0x5D, 0x43, 0x15, 0xBB, 0x26, 0x30, 0x54, 0x27, 0xF6, 0x45, 0xB4, 0x92, 0xE9, 0x35, 0x0C, 0x10
  ])
  private static let privateKey = "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
    + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471"
}
