import MeshCore

/// Captures the physical owner and session so retirement never looks up a successor.
@MainActor
final class ConnectionResource {
  let binding: SessionTransportBinding
  let transport: any MeshTransport
  let session: MeshCoreSession
  let wifi: (any WiFiMeshTransport)?
  private let ble: (any iOSMeshTransport)?
  private var retirement: Task<Void, Never>?
  private var physicalClose: Task<Void, Never>?

  init(
    binding: SessionTransportBinding,
    transport: any MeshTransport,
    session: MeshCoreSession,
    wifi: (any WiFiMeshTransport)?,
    ble: (any iOSMeshTransport)?
  ) {
    self.binding = binding
    self.transport = transport
    self.session = session
    self.wifi = wifi
    self.ble = ble
  }

  func revoke() {
    binding.revoke()
  }

  func cancelUnsubmittedWrites() async {
    await ble?.cancelUnsubmittedWrites(sessionBinding: binding)
  }

  func retire(closePhysical: Bool) async {
    revoke()
    await cancelUnsubmittedWrites()
    if closePhysical { await ble?.cancelPendingEstablishment(sessionBinding: binding) }
    let drain: Task<Void, Never>
    if let retirement {
      drain = retirement
    } else {
      drain = Task { [session, binding, wifi] in
        async let stopped: Void = session.stop(disconnectTransport: false, drainExchanges: true)
        if let wifi { await wifi.disconnect() }
        await stopped
        await binding.waitForQuiescence()
        await session.waitForQuiescence()
      }
      retirement = drain
    }
    await drain.value
    guard closePhysical, wifi == nil else { return }
    let close: Task<Void, Never>
    if let physicalClose {
      close = physicalClose
    } else {
      close = Task { [transport, binding] in
        await transport.disconnect(sessionBinding: binding)
      }
      physicalClose = close
    }
    await close.value
  }
}
