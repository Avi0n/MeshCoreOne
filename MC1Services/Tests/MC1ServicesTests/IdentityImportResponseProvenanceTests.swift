import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity import response provenance")
@MainActor
struct IdentityImportResponseProvenanceTests {
  @Test(arguments: [false, true], [false, true])
  func `a generic reply past its actual timeout cannot acknowledge or reject identity import`(
    oldReplyWasOK: Bool, cancelOrdinaryCaller: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.installedLink)
      let gate = Gate()
      harness.manager.testIdentityBeforeAdmission = { await gate.wait() }
      var importFinished = false
      let importing = harness.track(Task {
        defer { importFinished = true }
        try await source.services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(
            name: "Identity tail must not run", privateKey: CanonicalIdentityFixture.privateKeyHex,
            channels: [.init(name: "Unattributed tail", secret: String(repeating: "AB", count: 16))]
          ),
          sections: ConfigSections(nodeIdentity: true, channels: true), radioID: harness.deviceA.radioID
        )
      })
      do {
        try await waitUntil("config preflight should reach identity admission") { gate.arrived }
        await harness.bluetooth.setResponsePackets(
          for: .setName,
          packets: [oldReplyWasOK ? Data([ResponseCode.ok.rawValue]) : Data([ResponseCode.error.rawValue, 1])]
        )
        await harness.bluetooth.holdReplies(for: .setName)
        let ordinary = harness.track(Task {
          let _: Bool = try await source.resource.session.sendAndWait(
            PacketBuilder.setName("Earlier ordinary command"), matching: { event in
              switch event {
              case .ok, .error: true
              default: nil
              }
            }, timeout: 0.05
          )
        })
        try await waitUntil("ordinary exchange must be physically submitted after preflight") {
          await harness.bluetooth.sentData.contains { $0.first == CommandCode.setName.rawValue }
        }
        if cancelOrdinaryCaller { ordinary.cancel() }
        _ = await ordinary.result
        await source.resource.session.waitForQuiescence()
        if oldReplyWasOK { await harness.bluetooth.rejectNext(.importPrivateKey) }
        await harness.bluetooth.holdReplies(for: .importPrivateKey)
        gate.open()
        try await waitUntil("identity should either refuse uncertain history or submit its exchange") {
          if importFinished { return true }
          return await harness.bluetooth.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
        }
        await harness.bluetooth.releaseReplies(for: .setName)
        await harness.bluetooth.releaseReplies(for: .importPrivateKey)
        let result = await importing.result
        if case .success = result { Issue.record("Unattributed generic response completed the identity import") }
        let frames = await harness.bluetooth.sentData
        let importWasNotSubmitted = !frames.contains { $0.first == CommandCode.importPrivateKey.rawValue }
        let tailWasNotSubmitted = !frames.contains { $0.first == CommandCode.setChannel.rawValue }
        let nameWriteCount = frames.filter { $0.first == CommandCode.setName.rawValue }.count
        #expect(importWasNotSubmitted)
        #expect(tailWasNotSubmitted)
        #expect(nameWriteCount == 1)
        #expect(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id)?.publicKey == harness.deviceA.publicKey)
      } catch {
        gate.open()
        await harness.bluetooth.releaseAllGates()
        _ = await importing.result
        harness.manager.testIdentityBeforeAdmission = nil
        throw error
      }
      harness.manager.testIdentityBeforeAdmission = nil
      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      let fresh = try #require(harness.manager.services)
      await harness.bluetooth.rejectNext(.importPrivateKey, count: 0)
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      try await fresh.nodeConfigService.importConfig(
        MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
        sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
      )
      let keyWriteCount = await IdentityRecoveryFixture.keyWriteCount(harness)
      #expect(keyWriteCount == 1)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @MainActor
  private final class Gate {
    var arrived = false
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      arrived = true
      guard !opened else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      opened = true
      continuation?.resume()
      continuation = nil
    }
  }
}
