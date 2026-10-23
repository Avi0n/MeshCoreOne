import Foundation

extension MeshCoreSession {
  // MARK: - Region Requests

  /// Queries a repeater for its list of allowed regions.
  ///
  /// Sends an anonymous region request to the specified contact and waits for the
  /// repeater to respond with its configured region list.
  ///
  /// - Parameter contact: The repeater contact to query. Must have a full 32-byte public key.
  /// - Returns: An array of region name strings (e.g., `["Europe", "UK"]`).
  ///   Names prefixed with `$` are private regions requiring pre-shared keys.
  /// - Throws: ``MeshCoreError/timeout`` if no response is received,
  ///   ``MeshCoreError/deviceError(code:)`` if the firmware rejects the request,
  ///   ``MeshCoreError/parseError(_:)`` if the response is malformed.
  public func requestRegions(from contact: MeshContact) async throws -> [String] {
    let isFloodRouted = contact.outPathLength == 0xFF

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
      let result = try await requestResponseSerializer.withSerialization { [self] in
        try await performRegionsRequest(from: contact)
      }
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

  /// Sends the region request and matches its response. Runs inside the
  /// request/response serializer; flood-route setup and restore are handled by
  /// ``requestRegions(from:)`` as separate exchanges.
  private func performRegionsRequest(from contact: MeshContact) async throws -> [String] {
    let isFloodRouted = contact.outPathLength == 0xFF
    let pathLength: UInt8
    let path: Data
    if isFloodRouted {
      pathLength = 0
      path = Data()
    } else {
      pathLength = contact.outPathLength
      path = contact.outPath
    }

    return try await performBinaryExchange(
      request: PacketBuilder.sendAnonReq(
        to: contact.publicKey,
        type: .regions,
        pathLength: pathLength,
        path: path
      ),
      to: contact.publicKey,
      operation: "Regions"
    ) { payload, _ in
      try RegionsParser.parse(payload)
    }
  }
}
