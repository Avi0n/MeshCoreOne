import CoreBluetooth
import Foundation
@testable import MC1Services
import Testing

// MARK: - Error matchers

private func isAuthenticationFailed(_ error: BLEError?) -> Bool {
  if case .authenticationFailed = error { return true }
  return false
}

private func isConnectionTimeout(_ error: BLEError?) -> Bool {
  if case .connectionTimeout = error { return true }
  return false
}

private func isConnectionFailed(_ error: BLEError?) -> Bool {
  if case .connectionFailed = error { return true }
  return false
}

// MARK: - Error mapping

@Suite("ReconnectPolicy.makeConnectionError")
struct ReconnectPolicyErrorMappingTests {
  @Test
  func `CBATT auth/encryption codes map to BLEError.authenticationFailed`() {
    let authCodes = [
      CBATTError.insufficientAuthentication.rawValue,
      CBATTError.insufficientAuthorization.rawValue,
      CBATTError.insufficientEncryption.rawValue,
      CBATTError.insufficientEncryptionKeySize.rawValue
    ]

    for code in authCodes {
      let nsError = NSError(domain: CBATTErrorDomain, code: code)
      let result = ReconnectPolicy.makeConnectionError(nsError)
      guard case BLEError.authenticationFailed = result else {
        Issue.record("Expected .authenticationFailed for CBATTError code \(code), got \(result)")
        continue
      }
    }
  }

  @Test
  func `CBError.encryptionTimedOut maps to .connectionFailed, not authenticationFailed`() {
    // A single encryption timeout is transient (a backgrounded auto-reconnect
    // races iOS re-establishing the bond); only a definitive auth code, or a
    // majority of an exhausted retry budget, means a truly invalidated bond.
    let nsError = NSError(domain: CBErrorDomain, code: CBError.encryptionTimedOut.rawValue)
    let result = ReconnectPolicy.makeConnectionError(nsError)
    guard case BLEError.connectionFailed = result else {
      Issue.record("Expected .connectionFailed, got \(result)")
      return
    }
  }

  @Test
  func `CBError.peerRemovedPairingInformation maps to BLEError.authenticationFailed`() {
    let nsError = NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)
    let result = ReconnectPolicy.makeConnectionError(nsError)
    guard case BLEError.authenticationFailed = result else {
      Issue.record("Expected .authenticationFailed, got \(result)")
      return
    }
  }

  @Test
  func `Non-auth CBATT codes fall through to .connectionFailed`() {
    let nsError = NSError(
      domain: CBATTErrorDomain,
      code: CBATTError.requestNotSupported.rawValue,
      userInfo: [NSLocalizedDescriptionKey: "Request not supported"]
    )
    let result = ReconnectPolicy.makeConnectionError(nsError)
    guard case let BLEError.connectionFailed(msg) = result else {
      Issue.record("Expected .connectionFailed, got \(result)")
      return
    }
    #expect(msg == "Request not supported")
  }

  @Test
  func `Detection survives a localized description`() {
    // Simulate iOS localizing the auth-failure description into German.
    let nsError = NSError(
      domain: CBATTErrorDomain,
      code: CBATTError.insufficientAuthentication.rawValue,
      userInfo: [NSLocalizedDescriptionKey: "Authentifizierung ist unzureichend."]
    )
    let result = ReconnectPolicy.makeConnectionError(nsError)
    guard case BLEError.authenticationFailed = result else {
      Issue.record("Expected .authenticationFailed for localized German auth error, got \(result)")
      return
    }
  }

  @Test
  func `nil error uses fallback message`() {
    let result = ReconnectPolicy.makeConnectionError(nil, fallback: "Disconnected during setup")
    guard case let BLEError.connectionFailed(msg) = result else {
      Issue.record("Expected .connectionFailed, got \(result)")
      return
    }
    #expect(msg == "Disconnected during setup")
  }
}

// MARK: - Discovery stalls

/// Truth table for the discovery watchdogs. A peripheral that is not connected
/// during auto-reconnect is backed by an OS pending connect that never
/// expires, so that watchdog waits (regardless of the extension budget); only
/// a connected-but-wedged discovery consumes extensions and eventually tears
/// down as a silently invalidated bond.
@Suite("ReconnectPolicy discovery stalls")
struct ReconnectPolicyDiscoveryStallTests {
  @Test
  func `auto-reconnect with the link down waits for the pending connect`() {
    var policy = ReconnectPolicy()
    guard case .waitForPendingConnect = policy.resolveAutoReconnectStall(peripheralConnected: false) else {
      Issue.record("Expected .waitForPendingConnect")
      return
    }
  }

  @Test
  func `waiting never exhausts into teardown while the link is down`() {
    var policy = ReconnectPolicy()
    policy.discoveryTimeoutExtensions = ReconnectPolicy.maxDiscoveryTimeoutExtensions
    guard case .waitForPendingConnect = policy.resolveAutoReconnectStall(peripheralConnected: false) else {
      Issue.record("Expected .waitForPendingConnect with a spent budget")
      return
    }
  }

  @Test
  func `connected peripheral with budget extends the auto-reconnect window and consumes it`() {
    var policy = ReconnectPolicy()
    for expected in 1...ReconnectPolicy.maxDiscoveryTimeoutExtensions {
      guard case let .extendDiscoveryWindow(count, _) = policy.resolveAutoReconnectStall(peripheralConnected: true) else {
        Issue.record("Expected .extendDiscoveryWindow at extension \(expected)")
        return
      }
      #expect(count == expected)
    }
    #expect(policy.discoveryTimeoutExtensions == ReconnectPolicy.maxDiscoveryTimeoutExtensions)
  }

  @Test
  func `connected peripheral with exhausted budget escalates to an auth failure`() {
    var policy = ReconnectPolicy()
    policy.discoveryTimeoutExtensions = ReconnectPolicy.maxDiscoveryTimeoutExtensions
    guard case let .tearDown(error) = policy.resolveAutoReconnectStall(peripheralConnected: true) else {
      Issue.record("Expected .tearDown")
      return
    }
    #expect(isAuthenticationFailed(error))
  }

  @Test
  func `service discovery stall on a link that never reached connected is a plain timeout`() {
    var policy = ReconnectPolicy()
    policy.discoveryTimeoutExtensions = ReconnectPolicy.maxDiscoveryTimeoutExtensions
    guard case let .tearDown(error) = policy.resolveServiceDiscoveryStall(peripheralConnected: false) else {
      Issue.record("Expected .tearDown")
      return
    }
    #expect(isConnectionTimeout(error))
  }

  @Test
  func `service discovery stall on a connected peripheral extends until the budget is spent, then escalates`() {
    var policy = ReconnectPolicy()
    for expected in 1...ReconnectPolicy.maxDiscoveryTimeoutExtensions {
      guard case let .extendDiscoveryWindow(count, _) = policy.resolveServiceDiscoveryStall(peripheralConnected: true) else {
        Issue.record("Expected .extendDiscoveryWindow at extension \(expected)")
        return
      }
      #expect(count == expected)
    }
    guard case let .tearDown(error) = policy.resolveServiceDiscoveryStall(peripheralConnected: true) else {
      Issue.record("Expected .tearDown once the budget is spent")
      return
    }
    #expect(isAuthenticationFailed(error))
  }

  @Test
  func `a new generation resets the extension budget`() {
    var policy = ReconnectPolicy()
    policy.discoveryTimeoutExtensions = ReconnectPolicy.maxDiscoveryTimeoutExtensions
    policy.generationAdvanced()
    #expect(policy.discoveryTimeoutExtensions == 0)
    guard case .extendDiscoveryWindow(extensionCount: 1, _) = policy.resolveServiceDiscoveryStall(peripheralConnected: true) else {
      Issue.record("Expected a fresh first extension after generation advance")
      return
    }
  }
}

// MARK: - Connect-failure episodes

/// Classification of `didFailToConnect` streaks within one auto-reconnect
/// episode: transient failures retry, definitive bond failures escalate at
/// once, and an exhausted encryption-timeout majority escalates to guided
/// re-pair unless the bond completed a verified encrypted session within the
/// grace window.
@Suite("ReconnectPolicy connect failures")
struct ReconnectPolicyConnectFailureTests {
  private let deviceID = UUID()
  private var encryptionTimedOut: NSError {
    NSError(domain: CBErrorDomain, code: CBError.encryptionTimedOut.rawValue)
  }

  private func makePolicy(bondVerified: Date?) -> ReconnectPolicy {
    var policy = ReconnectPolicy()
    if let bondVerified {
      policy.recordBondVerification(deviceID: deviceID, at: bondVerified)
    }
    return policy
  }

  private func exhaustBudget(
    _ policy: inout ReconnectPolicy,
    error: NSError,
    now: Date = Date()
  ) -> ReconnectPolicy.ConnectFailureDecision {
    var last = policy.resolveConnectFailure(deviceID: deviceID, error: error, now: now)
    for _ in 1..<ReconnectPolicy.maxAutoReconnectConnectFailures {
      last = policy.resolveConnectFailure(deviceID: deviceID, error: error, now: now)
    }
    return last
  }

  @Test
  func `failures below the budget retry the pending connect`() {
    var policy = makePolicy(bondVerified: nil)
    for expected in 1..<ReconnectPolicy.maxAutoReconnectConnectFailures {
      let decision = policy.resolveConnectFailure(deviceID: deviceID, error: encryptionTimedOut, now: Date())
      guard case let .retryPendingConnect(count, _) = decision else {
        Issue.record("Expected .retryPendingConnect at failure \(expected), got \(decision)")
        return
      }
      #expect(count == expected)
    }
  }

  @Test
  func `a definitive bond error escalates immediately even with a recent verification`() {
    var policy = makePolicy(bondVerified: Date())
    let definitive = NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)

    let decision = policy.resolveConnectFailure(deviceID: deviceID, error: definitive, now: Date())

    guard case let .tearDown(error, .definitiveBondFailure) = decision else {
      Issue.record("Expected definitive-bond teardown, got \(decision)")
      return
    }
    #expect(isAuthenticationFailed(error))
    #expect(policy.autoReconnectConnectFailures == 0)
  }

  @Test
  func `exhausted encryption-timeout budget with a recently verified bond stays transient`() {
    let now = Date()
    var policy = makePolicy(bondVerified: now.addingTimeInterval(-60))

    let last = exhaustBudget(&policy, error: encryptionTimedOut, now: now)

    guard case let .tearDown(error, .fringeEncryptionGraced(verifiedAge)) = last else {
      Issue.record("Expected fringe-graced teardown, got \(last)")
      return
    }
    #expect(isConnectionFailed(error))
    #expect(verifiedAge == 60)
  }

  @Test
  func `a mixed majority of encryption timeouts with a recent bond stays transient`() {
    var policy = makePolicy(bondVerified: Date().addingTimeInterval(-60))
    let genericTimeout = NSError(domain: CBErrorDomain, code: CBError.connectionTimeout.rawValue)

    // 3 of 5 encryption timeouts is a strict majority.
    var last: ReconnectPolicy.ConnectFailureDecision?
    for error in [encryptionTimedOut, genericTimeout, encryptionTimedOut, genericTimeout, encryptionTimedOut] {
      last = policy.resolveConnectFailure(deviceID: deviceID, error: error, now: Date())
    }

    guard case let .tearDown(error, .fringeEncryptionGraced) = last else {
      Issue.record("Expected fringe-graced teardown, got \(String(describing: last))")
      return
    }
    #expect(isConnectionFailed(error))
  }

  @Test
  func `exhausted budget with a stale bond verification escalates to bond-suspect`() {
    let now = Date()
    let staleAge = ReconnectPolicy.bondVerificationGraceInterval + 60
    var policy = makePolicy(bondVerified: now.addingTimeInterval(-staleAge))

    let last = exhaustBudget(&policy, error: encryptionTimedOut, now: now)

    guard case let .tearDown(error, .bondSuspect(verifiedAge)) = last else {
      Issue.record("Expected bond-suspect teardown, got \(last)")
      return
    }
    #expect(isAuthenticationFailed(error))
    #expect(verifiedAge == staleAge)
  }

  @Test
  func `exhausted budget with no bond verification record escalates to bond-suspect`() {
    var policy = makePolicy(bondVerified: nil)

    let last = exhaustBudget(&policy, error: encryptionTimedOut)

    guard case let .tearDown(error, .bondSuspect(verifiedAge)) = last else {
      Issue.record("Expected bond-suspect teardown, got \(last)")
      return
    }
    #expect(isAuthenticationFailed(error))
    #expect(verifiedAge == nil)
  }

  @Test
  func `a verification for a different radio gives no shield`() {
    var policy = ReconnectPolicy()
    policy.recordBondVerification(deviceID: UUID(), at: Date())

    let last = exhaustBudget(&policy, error: encryptionTimedOut)

    guard case let .tearDown(error, .bondSuspect) = last else {
      Issue.record("Expected bond-suspect teardown, got \(last)")
      return
    }
    #expect(isAuthenticationFailed(error))
  }

  @Test
  func `a cleared verification stops shielding`() {
    var policy = makePolicy(bondVerified: Date().addingTimeInterval(-60))
    policy.clearBondVerification(deviceID: deviceID)

    let last = exhaustBudget(&policy, error: encryptionTimedOut)

    guard case .tearDown(_, .bondSuspect(verifiedAge: nil)) = last else {
      Issue.record("Expected unshielded bond-suspect teardown, got \(last)")
      return
    }
  }

  @Test
  func `an exhausted budget without an encryption-timeout majority surfaces the mapped error`() {
    var policy = makePolicy(bondVerified: nil)
    let genericTimeout = NSError(domain: CBErrorDomain, code: CBError.connectionTimeout.rawValue)

    let last = exhaustBudget(&policy, error: genericTimeout)

    guard case let .tearDown(error, .retryBudgetExhausted) = last else {
      Issue.record("Expected budget-exhausted teardown, got \(last)")
      return
    }
    #expect(isConnectionFailed(error))
  }

  @Test
  func `a re-established link clears the failure tally mid-episode`() {
    var policy = makePolicy(bondVerified: nil)
    for _ in 1..<ReconnectPolicy.maxAutoReconnectConnectFailures {
      _ = policy.resolveConnectFailure(deviceID: deviceID, error: encryptionTimedOut, now: Date())
    }
    #expect(policy.autoReconnectConnectFailures == ReconnectPolicy.maxAutoReconnectConnectFailures - 1)

    policy.linkReestablished()

    #expect(policy.autoReconnectConnectFailures == 0)
    #expect(policy.encryptionTimedOutConnectFailures == 0)
  }

  @Test
  func `a teardown resets the tallies so the next episode starts fresh`() {
    var policy = makePolicy(bondVerified: nil)
    _ = exhaustBudget(&policy, error: encryptionTimedOut)

    #expect(policy.autoReconnectConnectFailures == 0)
    #expect(policy.encryptionTimedOutConnectFailures == 0)
  }
}

// MARK: - Grace predicate

@Suite("ReconnectPolicy bond verification recency")
struct ReconnectPolicyGracePredicateTests {
  @Test
  func `bond verification recency predicate`() {
    let now = Date()
    let grace = ReconnectPolicy.bondVerificationGraceInterval

    #expect(!ReconnectPolicy.isBondRecentlyVerified(lastVerified: nil, now: now))
    #expect(ReconnectPolicy.isBondRecentlyVerified(lastVerified: now.addingTimeInterval(-1), now: now))
    #expect(ReconnectPolicy.isBondRecentlyVerified(lastVerified: now.addingTimeInterval(-grace + 1), now: now))
    #expect(!ReconnectPolicy.isBondRecentlyVerified(lastVerified: now.addingTimeInterval(-grace), now: now))
    // A clock set backward yields a future verification; err non-destructive.
    #expect(ReconnectPolicy.isBondRecentlyVerified(lastVerified: now.addingTimeInterval(60), now: now))
  }
}
