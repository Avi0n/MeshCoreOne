import MC1Services
import Observation

/// One visit and one Apply lease. Repeater adds `get repeat` / `set repeat` to that same pass.
@Observable
@MainActor
final class SharedNodeBehavior {
  var advertIntervalMinutes: Int?
  var floodAdvertIntervalHours: Int?
  var floodMaxHops: Int?
  private var originalAdvertIntervalMinutes: Int?
  private var originalFloodAdvertIntervalHours: Int?
  private var originalFloodMaxHops: Int?

  var advertIntervalError: String?
  var floodAdvertIntervalError: String?
  var floodMaxHopsError: String?

  private struct Field {
    let query: String
    let owned: NodeSettingsViewModel.OwnedSettingsField
    let value: ReferenceWritableKeyPath<SharedNodeBehavior, Int?>
    let original: ReferenceWritableKeyPath<SharedNodeBehavior, Int?>
    let error: ReferenceWritableKeyPath<SharedNodeBehavior, String?>
    let setCommand: (Int) -> String
    let parsed: (CLIResponse) -> Int?
  }

  private static let fields: [Field] = [
    Field(
      query: "get advert.interval",
      owned: .advertInterval,
      value: \.advertIntervalMinutes,
      original: \.originalAdvertIntervalMinutes,
      error: \.advertIntervalError,
      setCommand: { "set advert.interval \($0)" },
      parsed: { response in
        guard case let .advertInterval(minutes) = response else { return nil }
        return minutes
      }
    ),
    Field(
      query: "get flood.advert.interval",
      owned: .floodAdvertInterval,
      value: \.floodAdvertIntervalHours,
      original: \.originalFloodAdvertIntervalHours,
      error: \.floodAdvertIntervalError,
      setCommand: { "set flood.advert.interval \($0)" },
      parsed: { response in
        guard case let .floodAdvertInterval(hours) = response else { return nil }
        return hours
      }
    ),
    Field(
      query: "get flood.max",
      owned: .floodMaxHops,
      value: \.floodMaxHops,
      original: \.originalFloodMaxHops,
      error: \.floodMaxHopsError,
      setCommand: { "set flood.max \($0)" },
      parsed: { response in
        guard case let .floodMax(hops) = response else { return nil }
        return hops
      }
    )
  ]

  var hasValues: Bool {
    Self.fields.contains { self[keyPath: $0.value] != nil }
  }

  var isModified: Bool {
    Self.fields.contains { field in
      let current = self[keyPath: field.value]
      return current != nil && current != self[keyPath: field.original]
    }
  }

  var originalsReady: Bool {
    Self.fields.allSatisfy { self[keyPath: $0.original] != nil }
  }

  func revert() {
    for field in Self.fields {
      self[keyPath: field.value] = self[keyPath: field.original]
      self[keyPath: field.error] = nil
    }
  }

  func clear() {
    for field in Self.fields {
      self[keyPath: field.value] = nil
      self[keyPath: field.original] = nil
      self[keyPath: field.error] = nil
    }
  }

  struct ExtraQuery {
    let query: String
    let field: NodeSettingsViewModel.OwnedSettingsField
    let parse: @MainActor (String) -> Void
  }

  func registerLateRecovery(
    on helper: NodeSettingsViewModel,
    sectionHasError: @escaping @MainActor () -> Bool,
    setSectionError: @escaping @MainActor (Bool) -> Void
  ) {
    for field in Self.fields {
      helper.registerLateRecovery(query: field.query) { [weak self] value in
        guard let self,
              helper.isSettingsLoadCurrent(query: field.query, field: field.owned),
              let parsed = field.parsed(value) else { return }
        self[keyPath: field.value] = parsed
        self[keyPath: field.original] = parsed
        setSectionError(sectionHasError())
      }
    }
  }

  func fetch(
    using helper: NodeSettingsViewModel,
    extraQueries: [ExtraQuery] = [],
    setLoading: @escaping (Bool) -> Void,
    setError: @escaping (Bool) -> Void
  ) async {
    let visit = helper.captureSettingsVisit()
    guard helper.isSettingsVisitCurrent(visit) else { return }
    setLoading(true)
    setError(false)

    let queries = extraQueries + Self.fields.map { field in
      ExtraQuery(query: field.query, field: field.owned) { [weak self] response in
        guard let self,
              let parsed = field.parsed(CLIResponse.parse(response, forQuery: field.query)) else { return }
        self[keyPath: field.value] = parsed
        self[keyPath: field.original] = parsed
      }
    }
    var hadTimeout = false
    for query in queries {
      if await helper.loadCLIField(query: query.query, field: query.field, parse: query.parse) {
        hadTimeout = true
      }
    }

    helper.finishSettingsLoad(
      visit,
      timedOut: hadTimeout,
      setError: setError,
      setLoading: setLoading
    )
  }

  func apply(
    using helper: NodeSettingsViewModel,
    lease: ApplyLease,
    extraFields: [NodeSettingsViewModel.OwnedSettingsField] = [],
    sendExtras: @escaping () async throws -> Bool = { true },
    setSuccess: @escaping (Bool) -> Void
  ) async {
    let validation = NodeSettingsViewModel.validateBehaviorFields(
      advertInterval: advertIntervalMinutes,
      floodInterval: floodAdvertIntervalHours,
      floodMaxHops: floodMaxHops
    )
    advertIntervalError = validation.advertInterval
    floodAdvertIntervalError = validation.floodInterval
    floodMaxHopsError = validation.floodMaxHops
    if validation.hasErrors { return }

    let snapshots: [(field: Field, value: Int?)] = Self.fields.map { field in
      (field: field, value: self[keyPath: field.value])
    }
    let claims = helper.takeApplyOwnership(of: extraFields + Self.fields.map(\.owned))
    let leaseID = lease.begin()
    helper.errorMessage = nil
    defer { helper.finishApply(lease, id: leaseID, claims: claims) }

    do {
      var allSucceeded = try await sendExtras()
      for (field, snapshot) in snapshots {
        guard let snapshot, snapshot != self[keyPath: field.original] else { continue }
        let response = try await helper.sendAndWait(field.setCommand(snapshot))
        if case .ok = CLIResponse.parse(response) {
          self[keyPath: field.original] = snapshot
        } else {
          allSucceeded = false
        }
      }

      if allSucceeded {
        await helper.flashSuccess(lease: lease, id: leaseID, setSuccess: setSuccess)
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.someSettingsFailedToApply
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }
  }
}
