import MapLibre

enum OfflineMapNetwork {
  @MainActor
  static func configure() {
    guard let configuration = MLNNetworkConfiguration.sharedManager.sessionConfiguration else { return }
    var protocols = configuration.protocolClasses ?? []
    guard !protocols.contains(where: { $0 == OfflineMapURLProtocol.self }) else { return }
    protocols.insert(OfflineMapURLProtocol.self, at: 0)
    configuration.protocolClasses = protocols
    MLNNetworkConfiguration.sharedManager.sessionConfiguration = configuration
  }
}
