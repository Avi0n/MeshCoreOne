import Foundation

extension MeshCoreSession {

    // MARK: - Signing Commands

    /// Begins a signing operation.
    ///
    /// Initiates a multi-step signing process. After calling this, send data chunks
    /// with ``signData(_:)``, then finalize with ``signFinish(timeout:)``.
    ///
    /// - Returns: Maximum data size that can be signed in bytes.
    /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
    public func signStart() async throws -> Int {
        try await sendAndWait(PacketBuilder.signStart()) { event in
            if case .signStart(let maxLength) = event { return maxLength }
            return nil
        }
    }

    /// Sends a data chunk for signing.
    ///
    /// Must be called after ``signStart()`` and before ``signFinish(timeout:)``.
    ///
    /// - Parameter chunk: Data chunk to sign (typically up to 120 bytes).
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func signData(_ chunk: Data) async throws {
        try await sendSimpleCommand(PacketBuilder.signData(chunk))
    }

    /// Finalizes signing and retrieves the signature.
    ///
    /// Completes the signing operation started with ``signStart()`` after all
    /// data chunks have been sent with ``signData(_:)``.
    ///
    /// - Parameter timeout: Optional timeout override. Defaults to 3x default timeout.
    /// - Returns: The cryptographic signature (typically 64 bytes).
    /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
    public func signFinish(timeout: TimeInterval? = nil) async throws -> Data {
        let effectiveTimeout = timeout ?? (configuration.defaultTimeout * 3)
        return try await sendAndWait(PacketBuilder.signFinish(), matching: { event in
            if case .signature(let sig) = event { return sig }
            return nil
        }, timeout: effectiveTimeout)
    }

    /// Signs data using the device's private key.
    ///
    /// Handles the complete signing workflow: starts signing, sends data in chunks, and retrieves signature.
    ///
    /// - Parameters:
    ///   - data: The data to sign.
    ///   - chunkSize: Size of each chunk in bytes (default 120).
    ///   - timeout: Optional timeout for the finalization step.
    /// - Returns: The cryptographic signature.
    /// - Throws: ``MeshCoreError/dataTooLarge`` if data exceeds device limits.
    ///           ``MeshCoreError/timeout`` if any step times out.
    public func sign(_ data: Data, chunkSize: Int = 120, timeout: TimeInterval? = nil) async throws -> Data {
        let maxLength = try await signStart()

        guard data.count <= maxLength else {
            throw MeshCoreError.dataTooLarge(maxSize: maxLength, actualSize: data.count)
        }

        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let chunk = data[offset..<end]
            try await signData(Data(chunk))
            offset = end
        }

        return try await signFinish(timeout: timeout)
    }

    // MARK: - Control Data Commands

    /// Sends control data to the mesh network.
    ///
    /// Control data packets are used for network-level operations and diagnostics.
    ///
    /// - Parameters:
    ///   - type: Control data type identifier.
    ///   - payload: The control data payload.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func sendControlData(type: UInt8, payload: Data) async throws {
        try await sendSimpleCommand(PacketBuilder.sendControlData(type: type, payload: payload))
    }

    /// Sends a node discovery request to the mesh network.
    ///
    /// Broadcasts a request for nodes matching the filter criteria to respond.
    ///
    /// - Parameters:
    ///   - filter: Filter criteria for node types.
    ///   - prefixOnly: If `true`, only include public key prefixes in responses.
    ///   - tag: Optional request tag for correlation. Random value generated if nil.
    ///   - since: Optional timestamp to filter nodes seen since this time.
    /// - Returns: The tag used for this request (for correlating responses).
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func sendNodeDiscoverRequest(
        filter: UInt8,
        prefixOnly: Bool = true,
        tag: UInt32? = nil,
        since: Date? = nil
    ) async throws -> UInt32 {
        let actualTag = tag ?? UInt32.random(in: 1...UInt32.max)
        let sinceTimestamp = since.map { PacketBuilder.epochSeconds32($0) }
        let data = PacketBuilder.sendNodeDiscoverRequest(
            filter: filter,
            prefixOnly: prefixOnly,
            tag: actualTag,
            since: sinceTimestamp
        )
        try await sendSimpleCommand(data)
        return actualTag
    }

    /// Performs a factory reset on the device.
    ///
    /// This will erase all device configuration, contacts, and messages.
    /// The device will reboot and return to factory defaults.
    ///
    /// - Warning: This operation is irreversible.
    /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
    public func factoryReset() async throws {
        try await sendSimpleCommand(PacketBuilder.factoryReset())
    }
}
