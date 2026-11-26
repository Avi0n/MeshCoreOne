import Foundation

public extension MeshCoreSession {
  // MARK: - Region Requests

  /// Queries a repeater whose radio path has already been prepared for regions.
  func requestRegionsDirect(
    from contact: MeshContact,
    priority: MeshRequestPriority
  ) async throws -> [String] {
    try requireFullPublicKey(contact.publicKey, operation: "requestRegions")
    return try await performRegionsRequest(from: contact, priority: priority)
  }

  /// Queries a repeater for its list of allowed regions.
  func requestRegions(
    from contact: MeshContact,
    priority: MeshRequestPriority
  ) async throws -> [String] {
    try requireFullPublicKey(contact.publicKey, operation: "requestRegions")
    let isFloodRouted = contact.outPathLength == PacketBuilder.floodPathSentinel

    // Region requests need a direct out path. Restore flood routing after the
    // submitted exchange drains, even when the caller cancels during the write.
    do {
      if isFloodRouted {
        // Preserve the raw type because resetting the out path does not restore the type.
        let directContact = MeshContact(
          id: contact.id,
          publicKey: contact.publicKey,
          type: contact.type,
          typeRawValue: contact.typeRawValue,
          flags: contact.flags,
          outPathLength: 0,
          outPath: Data(),
          advertisedName: contact.advertisedName,
          lastAdvertisement: contact.lastAdvertisement,
          latitude: contact.latitude,
          longitude: contact.longitude,
          lastModified: contact.lastModified
        )
        try await sendSimpleCommand(PacketBuilder.updateContact(directContact))
      }
      let result = try await performRegionsRequest(from: contact, priority: priority)
      if isFloodRouted {
        await Task { try? await resetPath(publicKey: contact.publicKey) }.value
      }
      return result
    } catch {
      if isFloodRouted {
        await Task { try? await resetPath(publicKey: contact.publicKey) }.value
      }
      throw error
    }
  }

  private func performRegionsRequest(
    from contact: MeshContact,
    priority: MeshRequestPriority
  ) async throws -> [String] {
    let isFloodRouted = contact.outPathLength == PacketBuilder.floodPathSentinel
    let pathLength: UInt8
    let path: Data
    if isFloodRouted {
      pathLength = 0
      path = Data()
    } else {
      pathLength = contact.outPathLength
      path = contact.outPath
    }

    let spec = MeshExchangeSpec<[String]>(
      request: PacketBuilder.sendAnonReq(
        to: contact.publicKey,
        type: .regions,
        pathLength: pathLength,
        path: path
      ),
      publicKey: contact.publicKey,
      kind: .binary,
      operation: "Regions",
      acceptsBinaryResponse: true,
      routedEventFilter: { _ in false },
      matchRoutedEvent: nil,
      parseBinaryResponse: { payload, _ in
        try RegionsParser.parse(payload)
      },
      budget: { [overall = configuration.binaryRequestOverallTimeout] _ in overall },
      onTimeoutKnown: nil,
      loginPasswordDigest: nil,
      samePasswordExemption: false,
      onUnconsumedExit: nil
    )
    return try await performMeshExchange(spec, priority: priority)
  }
}
