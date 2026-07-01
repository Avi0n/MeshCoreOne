import Foundation

extension MeshCoreSession {
  // MARK: - Event Waiting

  /// Waits for a specific event type with optional filtering.
  ///
  /// Prefer ``sendAndWait(_:matching:timeout:)`` for command/response patterns to avoid race conditions.
  ///
  /// - Parameters:
  ///   - predicate: A closure that returns `true` for the event you're waiting for.
  ///   - timeout: Maximum time to wait in seconds. Uses `configuration.defaultTimeout` if `nil`.
  /// - Returns: The matching event, or `nil` if timeout occurred.
  public func waitForEvent(
    matching predicate: @escaping @Sendable (MeshEvent) -> Bool,
    timeout: TimeInterval? = nil
  ) async -> MeshEvent? {
    let effectiveTimeout = timeout ?? configuration.defaultTimeout
    let (subscriptionID, events) = await dispatcher.subscribeTracked()

    return await withTaskGroup(of: MeshEvent?.self) { group in
      group.addTask {
        for await event in events {
          if Task.isCancelled { return nil }
          if predicate(event) {
            return event
          }
        }
        return nil
      }

      group.addTask { [clock = self.clock] in
        try? await clock.sleep(for: .seconds(effectiveTimeout))
        return nil
      }

      let result = await group.next() ?? nil
      group.cancelAll()
      await self.dispatcher.finishSubscription(id: subscriptionID)
      return result
    }
  }

  /// Waits for an event matching an ``EventFilter`` with timeout.
  ///
  /// This method subscribes to events using a filtered subscription for efficiency,
  /// then waits for the first matching event or timeout.
  ///
  /// - Parameters:
  ///   - filter: The event filter to apply.
  ///   - timeout: Maximum time to wait. Uses `configuration.defaultTimeout` if `nil`.
  /// - Returns: The matching event, or `nil` if timeout occurred.
  ///
  /// ## Example
  ///
  /// ```swift
  /// // Wait for acknowledgement with specific code
  /// let filter = EventFilter.acknowledgement(code: expectedAck)
  /// if let event = await session.waitForEvent(filter: filter, timeout: 10.0) {
  ///     print("Received acknowledgement")
  /// }
  /// ```
  public func waitForEvent(
    filter: EventFilter,
    timeout: TimeInterval? = nil
  ) async -> MeshEvent? {
    let effectiveTimeout = timeout ?? configuration.defaultTimeout
    let (subscriptionID, stream) = await dispatcher.subscribeTracked(filter: filter.matches)

    return await withTaskGroup(of: MeshEvent?.self) { group in
      group.addTask {
        for await event in stream {
          if Task.isCancelled { return nil }
          return event
        }
        return nil
      }

      group.addTask { [clock = self.clock] in
        try? await clock.sleep(for: .seconds(effectiveTimeout))
        return nil
      }

      let result = await group.next() ?? nil
      group.cancelAll()
      await self.dispatcher.finishSubscription(id: subscriptionID)
      return result
    }
  }

  /// Sends a command and waits for a matching response.
  ///
  /// This method avoids race conditions by subscribing to events before sending the command.
  /// Events that do not satisfy the matcher, including unrelated `.error` events, are
  /// ignored until a matching response arrives or the timeout expires.
  ///
  /// - Parameters:
  ///   - data: The command data to send.
  ///   - predicate: A closure that matches and extracts the desired result from an event.
  ///   - timeout: The maximum time to wait for a response. Defaults to `configuration.defaultTimeout`.
  /// - Returns: The extracted result of type `T`.
  /// - Throws: ``MeshCoreError/timeout`` if no matching event is received within the timeout.
  public func sendAndWait<T: Sendable>(
    _ data: Data,
    matching predicate: @escaping @Sendable (MeshEvent) -> T?,
    timeout: TimeInterval? = nil
  ) async throws -> T {
    try await sendAndMatch(data, timeout: timeout) { event in
      if let result = predicate(event) {
        return .success(result)
      }
      return .ignore
    }
  }

  /// Sends a command and waits for either a success response or error.
  ///
  /// - Parameters:
  ///   - data: Command data to send.
  ///   - successPredicate: Predicate to match success events and extract result.
  ///   - errorMatcher: Optional matcher for request-specific error events. Errors that
  ///     do not match are ignored so unrelated commands cannot fail the active request.
  ///   - timeout: Optional timeout override.
  /// - Returns: The extracted result on success.
  /// - Throws: A matched ``MeshCoreError`` from `errorMatcher`,
  ///           ``MeshCoreError/timeout`` on timeout.
  func sendAndWaitWithError<T: Sendable>(
    _ data: Data,
    matching successPredicate: @escaping @Sendable (MeshEvent) -> T?,
    errorMatcher: (@Sendable (MeshEvent) -> MeshCoreError?)? = nil,
    timeout: TimeInterval? = nil
  ) async throws -> T {
    try await sendAndMatch(data, timeout: timeout) { event in
      if let error = errorMatcher?(event) {
        return .failure(error)
      }
      if let result = successPredicate(event) {
        return .success(result)
      }
      return .ignore
    }
  }

  /// Standard error matcher that converts `.error` events into ``MeshCoreError/deviceError(code:)``.
  static let deviceErrorMatcher: @Sendable (MeshEvent) -> MeshCoreError? = { event in
    if case let .error(code) = event {
      return MeshCoreError.deviceError(code: code ?? 0)
    }
    return nil
  }

  enum ResponseDisposition<T: Sendable> {
    case success(T)
    case failure(MeshCoreError)
    case ignore
  }

  func sendAndMatch<T: Sendable>(
    _ data: Data,
    timeout: TimeInterval? = nil,
    matching matcher: @escaping @Sendable (MeshEvent) -> ResponseDisposition<T>
  ) async throws -> T {
    try await requestResponseSerializer.withSerialization { [self] in
      let effectiveTimeout = timeout ?? configuration.defaultTimeout

      // Subscribe before sending to avoid race condition, then ignore all
      // non-matching events until this request sees its own response.
      let (subscriptionID, events) = await dispatcher.subscribeTracked()

      do {
        // Send after subscribing
        try await transport.send(data)

        return try await withThrowingTaskGroup(of: T?.self) { group in
          group.addTask {
            for await event in events {
              switch matcher(event) {
              case let .success(result):
                return result
              case let .failure(error):
                throw error
              case .ignore:
                continue
              }
            }
            return nil
          }

          group.addTask { [clock = self.clock] in
            try await clock.sleep(for: .seconds(effectiveTimeout))
            return nil
          }

          do {
            if let result = try await group.next() ?? nil {
              group.cancelAll()
              await dispatcher.finishSubscription(id: subscriptionID)
              return result
            }
            group.cancelAll()
            await dispatcher.finishSubscription(id: subscriptionID)
            throw MeshCoreError.timeout
          } catch {
            group.cancelAll()
            await dispatcher.finishSubscription(id: subscriptionID)
            throw error
          }
        }
      } catch {
        await dispatcher.finishSubscription(id: subscriptionID)
        throw error
      }
    }
  }

  // MARK: - Command Helpers

  /// Sends a command and waits for an "OK" response from the device.
  func sendSimpleCommand(_ data: Data) async throws {
    let _: Bool = try await sendAndWaitWithError(
      data,
      matching: { event in
        if case let .ok(value) = event, value == nil {
          return true
        }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  func requireFullPublicKey(_ publicKey: Data, operation: String) throws {
    guard publicKey.count == PacketBuilder.publicKeySize else {
      throw MeshCoreError.invalidInput("Full \(PacketBuilder.publicKeySize)-byte public key required for \(operation)")
    }
  }
}
