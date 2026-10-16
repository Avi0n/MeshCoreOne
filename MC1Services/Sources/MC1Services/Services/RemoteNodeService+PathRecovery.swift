import Foundation
import MeshCore

extension RemoteNodeService {
  // MARK: - Direct-path flood recovery

  /// CLI path recovery: run `operation`, and on a direct-path mesh timeout reset
  /// the contact path to flood and run once more. Already-flood contacts skip
  /// reset. A failed reset rethrows the original timeout. Binary admin waits use
  /// `performBinaryExchange` instead.
  func performWithDirectPathFloodRecovery<T: Sendable>(
    radioID: UUID,
    publicKey: Data,
    operationName: String,
    operation: () async throws -> T
  ) async throws -> T {
    do {
      return try await operation()
    } catch {
      guard Self.isMeshTimeout(error) else { throw error }
      guard await isDirectRouted(radioID: radioID, publicKey: publicKey) else {
        throw error
      }

      let firstTimeout = error
      logger.info("\(operationName): direct-path timeout; resetting path to flood and retrying once")
      do {
        try await resetContactPathToFlood(radioID: radioID, publicKey: publicKey)
      } catch is CancellationError {
        throw CancellationError()
      } catch {
        logger.warning(
          "\(operationName): path reset failed (\(error.localizedDescription)); not retrying"
        )
        throw firstTimeout
      }

      do {
        return try await operation()
      } catch {
        if Self.isMeshTimeout(error) {
          logger.warning("\(operationName): flood retry also timed out")
        }
        throw error
      }
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

  /// True when the local contact is not flood-routed. Missing contacts count as
  /// direct so `resetPath` can still clear a radio-side path.
  private func isDirectRouted(radioID: UUID, publicKey: Data) async -> Bool {
    guard let contact = try? await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) else {
      return true
    }
    return !contact.isFloodRouted
  }

  /// Clears the companion out-path via `resetPath` and mirrors flood on the local contact.
  private func resetContactPathToFlood(radioID: UUID, publicKey: Data) async throws {
    try await session.resetPath(publicKey: publicKey)

    guard let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) else {
      return
    }
    let frame = contact.floodedContactFrame(asOf: UInt32(Date().timeIntervalSince1970))
    do {
      _ = try await dataStore.saveContact(radioID: radioID, from: frame)
    } catch {
      // Radio path is already flood; a local save failure must not abort the retry.
      logger.warning("Path reset on radio but failed to sync local contact: \(error)")
    }
  }
}
