// ReconnectPolicy.swift
@preconcurrency import CoreBluetooth
import Foundation

/// The reconnect policy: classifies BLE link failures as transient (retry,
/// extend, wait) or escalating (tear down toward guided re-pair). Owns every
/// classification input — failure tallies, the discovery-extension budget,
/// and bond-verification recency — while `BLEStateMachine` keeps the
/// CoreBluetooth choreography that executes the decisions.
///
/// A plain value type: inputs carry their own timestamps and the policy never
/// reads the clock, so every decision is reproducible from its inputs. Each
/// resolve method returns only the decisions its call site can act on.
struct ReconnectPolicy {
  // MARK: - Budgets and grace

  /// Max times a discovery watchdog defers teardown while the peripheral is
  /// already connected, before forcing a reconnect. Bounds recovery so a
  /// genuinely wedged-but-connected link still tears down eventually.
  static let maxDiscoveryTimeoutExtensions = 2

  /// Max consecutive `didFailToConnect` callbacks tolerated within one
  /// auto-reconnect episode before the machine gives up and notifies loss.
  /// Bounds re-arming so a radio that fast-rejects every connect cannot spin here.
  static let maxAutoReconnectConnectFailures = 5

  /// How long after a verified encrypted session an exhausted encryption-timeout
  /// budget is still treated as transient rather than a suspect bond. Encryption
  /// timeouts at the edge of BLE range are indistinguishable from an invalidated
  /// bond attempt-by-attempt; a bond that completed an encrypted session this
  /// recently is near-certainly healthy, while a genuinely dead bond can never
  /// refresh the verification and escalates once the grace elapses.
  static let bondVerificationGraceInterval: TimeInterval = 6 * 60 * 60

  // MARK: - State

  /// Consecutive `didFailToConnect` callbacks in the current auto-reconnect
  /// episode. Reset when a link is re-established and when the episode ends.
  var autoReconnectConnectFailures = 0

  /// How many of `autoReconnectConnectFailures` carried `CBError.encryptionTimedOut`.
  /// A majority routes an exhausted episode to guided re-pair, since repeated
  /// encryption timeouts are the ambiguous in-app signature of an invalidated bond.
  var encryptionTimedOutConnectFailures = 0

  /// Number of times a discovery watchdog has deferred teardown within the
  /// current generation because the peripheral was already connected.
  /// Reset by `generationAdvanced()`.
  var discoveryTimeoutExtensions = 0

  /// When each device's bond last completed a verified encrypted session.
  /// Seeded from persistence at wiring time so a verification from a previous
  /// launch still shields, refreshed on every verified session, and cleared
  /// when the device's pairing is forgotten.
  var bondVerificationDates: [UUID: Date] = [:]

  // MARK: - Bookkeeping

  /// The link re-established mid-episode, breaking the failure streak.
  mutating func linkReestablished() {
    resetFailureTallies()
  }

  /// A new auto-reconnect episode started (disconnect, restoration).
  mutating func episodeBegan() {
    resetFailureTallies()
  }

  /// A new connection generation started; the extension budget resets.
  mutating func generationAdvanced() {
    discoveryTimeoutExtensions = 0
  }

  /// The device's bond completed a verified encrypted session at `date`.
  mutating func recordBondVerification(deviceID: UUID, at date: Date) {
    bondVerificationDates[deviceID] = date
  }

  /// Refreshes an existing verification's timestamp; never creates one. Only a
  /// completed app-layer handshake is evidence that a bond verified, so a
  /// forgotten pairing has no entry and a keepalive tick cannot re-shield it,
  /// whichever order the clear and the tick reach the actor in.
  /// - Returns: `true` when an existing stamp was updated.
  @discardableResult
  mutating func refreshBondVerification(deviceID: UUID, at date: Date) -> Bool {
    guard bondVerificationDates[deviceID] != nil else { return false }
    bondVerificationDates[deviceID] = date
    return true
  }

  /// The device's pairing was forgotten; its verification must stop shielding.
  mutating func clearBondVerification(deviceID: UUID) {
    bondVerificationDates[deviceID] = nil
  }

  // MARK: - Connect-failure classification

  enum ConnectFailureDecision {
    /// Re-issue the pending connect; the episode continues.
    case retryPendingConnect(failureCount: Int, budget: Int)
    /// End the episode, surfacing `error` through `onDisconnection`.
    case tearDown(error: BLEError, reason: TeardownReason)
  }

  /// Why a connect-failure `.tearDown` was chosen; drives the diagnostic log
  /// line. `verifiedAge` is the time since the bond's last verified encrypted
  /// session at decision time, nil when it never verified.
  enum TeardownReason {
    case definitiveBondFailure
    case fringeEncryptionGraced(verifiedAge: TimeInterval?)
    case bondSuspect(verifiedAge: TimeInterval?)
    case retryBudgetExhausted
  }

  /// `didFailToConnect` arrived while auto-reconnecting. A transient failure
  /// re-issues the connect and stays in the episode. Only a definitive bond
  /// failure tears down at once; exhausting the bounded budget on encryption
  /// timeouts escalates to auth failure — unless the bond verified an
  /// encrypted session recently — so an invalidated bond still reaches guided
  /// re-pair.
  mutating func resolveConnectFailure(deviceID: UUID, error: Error?, now: Date) -> ConnectFailureDecision {
    if Self.isDefinitiveAuthFailure(error) {
      resetFailureTallies()
      return .tearDown(error: .authenticationFailed, reason: .definitiveBondFailure)
    }

    autoReconnectConnectFailures += 1
    if Self.isEncryptionTimedOut(error) {
      encryptionTimedOutConnectFailures += 1
    }

    if autoReconnectConnectFailures < Self.maxAutoReconnectConnectFailures {
      return .retryPendingConnect(
        failureCount: autoReconnectConnectFailures,
        budget: Self.maxAutoReconnectConnectFailures
      )
    }

    // An encryption-timeout majority is the ambiguous signature of an
    // invalidated bond, but it is also what a healthy bond produces when the
    // user lingers at the edge of BLE range. A recently verified bond tears
    // down as transient (the watchdog keeps retrying); a dead bond can never
    // refresh its verification, so it still escalates once the grace elapses.
    let majorityEncryptionTimeouts = encryptionTimedOutConnectFailures * 2 > autoReconnectConnectFailures
    resetFailureTallies()

    guard majorityEncryptionTimeouts else {
      return .tearDown(error: Self.makeConnectionError(error), reason: .retryBudgetExhausted)
    }

    let lastVerified = bondVerificationDates[deviceID]
    let verifiedAge = lastVerified.map { now.timeIntervalSince($0) }
    if Self.isBondRecentlyVerified(lastVerified: lastVerified, now: now) {
      return .tearDown(
        error: .connectionFailed("Encryption timed out repeatedly near range limit"),
        reason: .fringeEncryptionGraced(verifiedAge: verifiedAge)
      )
    }
    return .tearDown(error: .authenticationFailed, reason: .bondSuspect(verifiedAge: verifiedAge))
  }

  // MARK: - Discovery-stall classification

  enum ServiceDiscoveryStallDecision {
    /// Give discovery another window instead of tearing down a live link.
    case extendDiscoveryWindow(extensionCount: Int, budget: Int)
    /// Cancel the connection and fail the in-flight connect with `error`.
    case tearDown(error: BLEError)
  }

  enum AutoReconnectStallDecision {
    /// Keep the OS pending connect armed and re-arm the watchdog.
    case waitForPendingConnect
    /// Give discovery another window instead of tearing down a live link.
    case extendDiscoveryWindow(extensionCount: Int, budget: Int)
    /// End the episode, surfacing `error` through `onDisconnection`.
    case tearDown(error: BLEError)
  }

  /// The service-discovery watchdog elapsed on an established link. When the
  /// peripheral is already connected, the BLE link is up and a discovery
  /// callback is in flight or merely slow; tearing it down kills a working
  /// connection, so the window extends a bounded number of times. A link that
  /// reached `.connected` yet never completed discovery across the full budget
  /// is the strongest in-app signal of a silently invalidated bond —
  /// CoreBluetooth delivers no error — so it surfaces as
  /// `.authenticationFailed` and routes into guided re-pair instead of a
  /// generic timeout retry loop. A link that never reached `.connected` is a
  /// plain connection timeout.
  mutating func resolveServiceDiscoveryStall(peripheralConnected: Bool) -> ServiceDiscoveryStallDecision {
    guard peripheralConnected else {
      return .tearDown(error: .connectionTimeout)
    }
    if let extended = consumeDiscoveryExtension() {
      return .extendDiscoveryWindow(extensionCount: extended, budget: Self.maxDiscoveryTimeoutExtensions)
    }
    return .tearDown(error: .authenticationFailed)
  }

  /// The auto-reconnect discovery watchdog elapsed. Same connected-stall
  /// handling as service discovery, except a link that is not `.connected`
  /// here is backed by an OS pending connect that never expires; cancelling it
  /// abandons a reconnection iOS would complete once the radio is back in
  /// range, so the watchdog waits without consuming extension budget.
  mutating func resolveAutoReconnectStall(peripheralConnected: Bool) -> AutoReconnectStallDecision {
    guard peripheralConnected else {
      return .waitForPendingConnect
    }
    if let extended = consumeDiscoveryExtension() {
      return .extendDiscoveryWindow(extensionCount: extended, budget: Self.maxDiscoveryTimeoutExtensions)
    }
    return .tearDown(error: .authenticationFailed)
  }

  /// Consumes one discovery-window extension, or returns nil when the budget
  /// is spent. Returns the new extension count for the watchdog's log line.
  private mutating func consumeDiscoveryExtension() -> Int? {
    guard discoveryTimeoutExtensions < Self.maxDiscoveryTimeoutExtensions else { return nil }
    discoveryTimeoutExtensions += 1
    return discoveryTimeoutExtensions
  }

  private mutating func resetFailureTallies() {
    autoReconnectConnectFailures = 0
    encryptionTimedOutConnectFailures = 0
  }

  // MARK: - Error classification

  /// Maps a CoreBluetooth error to a typed BLEError. The CBATTError auth/encryption
  /// family and `CBError.peerRemovedPairingInformation` are definitive bond failures
  /// mapped to `.authenticationFailed`, so detection survives iOS localizing the
  /// description. A lone `CBError.encryptionTimedOut` is transient and stays
  /// `.connectionFailed`; connect-failure resolution escalates it only when it
  /// dominates an exhausted auto-reconnect retry budget.
  static func makeConnectionError(_ error: Error?, fallback: String = "Unknown error") -> BLEError {
    if let nsError = error as NSError? {
      if nsError.domain == CBATTErrorDomain {
        switch nsError.code {
        case CBATTError.insufficientAuthentication.rawValue,
             CBATTError.insufficientAuthorization.rawValue,
             CBATTError.insufficientEncryption.rawValue,
             CBATTError.insufficientEncryptionKeySize.rawValue:
          return .authenticationFailed
        default:
          break
        }
      }
      if nsError.domain == CBErrorDomain,
         nsError.code == CBError.peerRemovedPairingInformation.rawValue {
        return .authenticationFailed
      }
    }
    return .connectionFailed(error?.localizedDescription ?? fallback)
  }

  /// Whether an error is a definitive bond failure that must not be retried:
  /// any error `makeConnectionError` classifies as `.authenticationFailed`.
  static func isDefinitiveAuthFailure(_ error: Error?) -> Bool {
    if case .authenticationFailed = makeConnectionError(error) { return true }
    return false
  }

  /// Whether an error is `CBError.encryptionTimedOut` — transient on its own, but
  /// the ambiguous signature of an invalidated bond when it recurs.
  static func isEncryptionTimedOut(_ error: Error?) -> Bool {
    guard let nsError = error as NSError? else { return false }
    return nsError.domain == CBErrorDomain && nsError.code == CBError.encryptionTimedOut.rawValue
  }

  /// Whether a bond verification is recent enough to shield an exhausted
  /// encryption-timeout budget from bond-suspect escalation. A missing date
  /// (never verified) gives no shield. A future date (clock set backward)
  /// counts as recent — the non-destructive direction.
  static func isBondRecentlyVerified(
    lastVerified: Date?,
    now: Date,
    grace: TimeInterval = bondVerificationGraceInterval
  ) -> Bool {
    guard let lastVerified else { return false }
    return now.timeIntervalSince(lastVerified) < grace
  }
}
