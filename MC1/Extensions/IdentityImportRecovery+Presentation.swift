import MC1Services

extension IdentityImportRecovery {
  var targetDescription: String {
    switch capturedConnectionMethod {
    case let .bluetooth(peripheralUUID, _):
      "\(L10n.Settings.Bluetooth.header): \(peripheralUUID.uuidString)"
    case let .wifi(host, port, _):
      "\(L10n.Settings.Connection.Link.wifi): \(host):\(port)"
    }
  }
}
