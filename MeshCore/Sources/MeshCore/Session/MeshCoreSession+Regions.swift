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

        // Firmware requires isRouteDirect() for region requests. For flood-routed
        // contacts, temporarily set the contact to zero-hop direct on the firmware,
        // matching the Python reference (base.py:269-273). The zero-hop write, the
        // region exchange, and the restore are each their own serialized exchange so
        // none nests inside the request/response serializer the others acquire.
        if isFloodRouted {
            // Route through PacketBuilder.updateContact so the raw type byte survives instead of
            // being coerced to .chat by the typed overload; the restore below only touches
            // out_path_len, so any coercion here would be permanent.
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

        do {
            let result = try await requestResponseSerializer.withSerialization { [self] in
                try await performRegionsRequest(from: contact)
            }
            if isFloodRouted {
                try? await resetPath(publicKey: contact.publicKey)
            }
            return result
        } catch {
            if isFloodRouted {
                try? await resetPath(publicKey: contact.publicKey)
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
