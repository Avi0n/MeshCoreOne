import Foundation
import MeshCore

extension RemoteNodeService {
  // MARK: - Direct-path flood recovery

  /// Probes over flood on direct-path CLI timeout inside `attemptFloodFallback`; settles
  /// `reciprocalPathSettle` and retries once only for read-only queries.
  func performWithDirectPathFloodRecovery<T: Sendable>(
    radioID: UUID,
    publicKey: Data,
    isSensor: Bool,
    isRoom: Bool,
    isReadOnlyQuery: Bool,
    operationName: String,
    operation: () async throws -> T
  ) async throws -> T {
    do {
      return try await operation()
    } catch {
      guard Self.isMeshTimeout(error),
            let owner = contactRadioOwner.resolve() else {
        throw error
      }

      let firstTimeout = error
      logger.info("\(operationName): direct-path timeout; probing via flood fallback")
      do {
        try await owner.attemptFloodFallback(
          radioID: radioID,
          publicKey: publicKey,
          rethrowIfUnavailable: firstTimeout
        ) {
          if isSensor {
            _ = try await self.session.requestTelemetry(from: publicKey, priority: .interactive)
          } else {
            let contactType: ContactType = isRoom ? .room : .repeater
            _ = try await self.session.requestStatus(
              from: publicKey,
              type: contactType,
              priority: .interactive
            )
          }
        }
      } catch is CancellationError {
        throw CancellationError()
      } catch {
        throw firstTimeout
      }

      try await clock.sleep(for: RemoteOperationTimeoutPolicy.reciprocalPathSettle)
      guard isReadOnlyQuery else {
        logger.info("\(operationName): route repaired; not retrying non-read-only CLI command")
        throw RemoteNodeError.timeout
      }

      return try await operation()
    }
  }

  /// True when the error is a mesh wait timeout (session or outer wrapper).
  static func isMeshTimeout(_ error: Error) -> Bool {
    if error is TimeoutError { return true }
    if case .timeout = error as? RemoteNodeError { return true }
    if case .timeout = error as? MeshCoreError { return true }
    if case let .sessionError(inner) = error as? RemoteNodeError, case .timeout = inner {
      return true
    }
    return false
  }
}
