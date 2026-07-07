import Accessibility
import Foundation
import MC1Services
import os

enum PingError: Error {
  case notConnected
  case timeout
}

@MainActor
enum PingHelper {
  private static let logger = Logger(subsystem: "com.mc1", category: "Ping")

  /// Send a zero-hop trace ping to a contact and return the timed result.
  /// Posts a VoiceOver announcement on completion.
  static func zeroHopPing(contact: ContactDTO, appState: AppState) async -> PingResult {
    let startTime = ContinuousClock.now
    let tag = UInt32.random(in: 0..<UInt32.max)

    do {
      guard let services = appState.services else {
        throw PingError.notConnected
      }

      let device = appState.connectedDevice
      let pathData = Data(contact.publicKey.prefix(device?.traceHashSize ?? 1))

      // Subscribe before sending the trace: registration is synchronous,
      // so a fast radio response cannot arrive ahead of the listener.
      let events = services.advertisementService.events()

      let (snrThere, snrBack) = try await withThrowingTaskGroup(
        of: (snrThere: Double, snrBack: Double).self
      ) { group in
        group.addTask {
          for await event in events {
            if case let .traceSnrObserved(notifTag, localSnr, remoteSnr, _) = event,
               notifTag == tag {
              return (snrThere: remoteSnr ?? 0, snrBack: localSnr)
            }
          }
          throw CancellationError()
        }

        let sentInfo = try await services.binaryProtocolService.sendTrace(
          tag: tag,
          flags: device?.pathHashMode ?? 0,
          path: pathData
        )

        let timeoutSeconds = FirmwareSuggestedTimeout.sanitizedSeconds(
          suggestedTimeoutMs: sentInfo.suggestedTimeoutMs,
          profile: .zeroHop
        )
        group.addTask {
          try await Task.sleep(for: .seconds(timeoutSeconds))
          throw PingError.timeout
        }

        guard let result = try await group.next() else {
          throw PingError.timeout
        }
        group.cancelAll()
        return result
      }

      let elapsed = ContinuousClock.now - startTime
      let latencyMs = Int(elapsed / .milliseconds(1))

      let announcement = L10n.Contacts.Contacts.Detail.pingSuccessAnnouncement(latencyMs)
      AccessibilityNotification.Announcement(announcement).post()
      return .success(latencyMs: latencyMs, snrThere: snrThere, snrBack: snrBack)
    } catch {
      logger.error("Ping failed: \(error.localizedDescription)")
      let announcement = L10n.Contacts.Contacts.Detail.pingFailureAnnouncement
      AccessibilityNotification.Announcement(announcement).post()
      return .error(L10n.Contacts.Contacts.Detail.pingNoResponse)
    }
  }
}
