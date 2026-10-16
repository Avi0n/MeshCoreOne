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

  /// Consecutive `didFailToConnect` callbacks in one auto-reconnect episode
  /// before `resolveConnectFailure` classifies hold versus tear-down.
  static let maxAutoReconnectConnectFailures = 5

  /// After a verified encrypted session, an exhausted encryption-timeout
  /// majority returns `.continueEpisodeAfterBudget` rather than `.bondSuspect`.
  /// Outside this window a dead bond escalates only while the app is active.
  static let bondVerificationGraceInterval: TimeInterval = 6 * 60 * 60

  // MARK: - State

  /// Consecutive `didFailToConnect` callbacks in the current auto-reconnect
  /// episode. Reset when a link is re-established and when the episode ends.
  var autoReconnectConnectFailures = 0

  /// How many of `autoReconnectConnectFailures` carried `CBError.encryptionTimedOut`.
  /// A majority is the ambiguous dead-bond signature used at budget exhaust.
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
    /// Budget spent: re-issue `connect` and keep the episode.
    /// `didFailToConnect` already consumed the pending connect; tearing
    /// down would leave only a watchdog whose sleep freezes when suspended.
    case continueEpisodeAfterBudget(reason: BudgetHoldReason)
    /// End the episode, surfacing `error` through `onDisconnection`.
    case tearDown(error: BLEError, reason: TeardownReason)
  }

  /// Why the episode continued after the connect-failure budget was spent.
  /// `verifiedAge` is the time since the bond's last verified encrypted
  /// session at decision time, nil when it never verified.
  enum BudgetHoldReason {
    case fringeEncryptionGraced(verifiedAge: TimeInterval?)
    case backgroundHold
  }

  /// Why a connect-failure `.tearDown` was chosen; drives the diagnostic log
  /// line. `verifiedAge` is the time since the bond's last verified encrypted
  /// session at decision time, nil when it never verified.
  enum TeardownReason {
    case definitiveBondFailure
    case bondSuspect(verifiedAge: TimeInterval?)
    case retryBudgetExhausted
  }

  /// `didFailToConnect` while auto-reconnecting. A definitive bond failure
  /// tears down at once; otherwise re-issue until the budget, then hold
  /// (recently verified, or inactive) or tear down (active and unshielded).
  mutating func resolveConnectFailure(
    deviceID: UUID,
    error: Error?,
    now: Date,
    appActive: Bool
  ) -> ConnectFailureDecision {
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

    // Encryption-timeout majority is both a dead-bond signature and fringe-range
    // noise. Keep the pending connect when recently verified or inactive;
    // escalate to `.bondSuspect` only when the app is active and grace has elapsed.
    let majorityEncryptionTimeouts = encryptionTimedOutConnectFailures * 2 > autoReconnectConnectFailures
    resetFailureTallies()

    let lastVerified = bondVerificationDates[deviceID]
    let verifiedAge = lastVerified.map { now.timeIntervalSince($0) }

    if majorityEncryptionTimeouts, Self.isBondRecentlyVerified(lastVerified: lastVerified, now: now) {
      return .continueEpisodeAfterBudget(reason: .fringeEncryptionGraced(verifiedAge: verifiedAge))
    }
    if !appActive {
      return .continueEpisodeAfterBudget(reason: .backgroundHold)
    }
    if majorityEncryptionTimeouts {
      return .tearDown(error: .authenticationFailed, reason: .bondSuspect(verifiedAge: verifiedAge))
    }
    return .tearDown(error: Self.makeConnectionError(error), reason: .retryBudgetExhausted)
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
  /// description. A lone `CBError.encryptionTimedOut` stays `.connectionFailed`;
  /// `resolveConnectFailure` holds or escalates it from the exhausted budget.
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
