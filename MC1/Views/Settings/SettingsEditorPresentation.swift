import Foundation
import MC1Services
import Observation

@Observable
@MainActor
final class SettingsEditorPresentation {
  enum Kind {
    case regionAdd
    case deviceName
  }

  struct Request: Identifiable {
    let id = UUID()
    let kind: Kind
    let radioID: UUID
    let rootNavigationGeneration: Int
  }

  struct Scope: Equatable {
    let selectedTab: Int
    let root: SettingsDetail?
    let descendants: [SettingsSubpage]
    let radioID: UUID?
    let supportsRegions: Bool
    let rootNavigationGeneration: Int

    func admits(_ kind: Kind, radioID: UUID) -> Bool {
      guard selectedTab == AppTab.settings.rawValue, self.radioID == radioID else { return false }
      switch kind {
      case .deviceName:
        return root == .deviceInfo && descendants.isEmpty
      case .regionAdd:
        return root == .advanced && descendants.last == .manageRegions && supportsRegions
      }
    }
  }

  typealias Submission = @MainActor (String) -> Void
  typealias RegionsProvider = @MainActor () -> [String]
  typealias ScopeProvider = @MainActor () -> Scope
  typealias NameRequest = @MainActor (String, @escaping Submission) -> Void
  typealias RegionRequest = @MainActor (@escaping RegionsProvider, @escaping Submission) -> Void

  private(set) var request: Request?
  private(set) var draft = ""
  private(set) var validationMessage: String?
  private(set) var isPresented = false

  @ObservationIgnored private var scopeProvider: ScopeProvider?
  @ObservationIgnored private var knownRegionsProvider: RegionsProvider?
  @ObservationIgnored private var onSubmit: Submission?

  private var scope: Scope? {
    scopeProvider?()
  }

  private var knownRegions: [String] {
    knownRegionsProvider?() ?? []
  }

  func presentDeviceName(
    _ name: String,
    radioID: UUID?,
    scope: @escaping ScopeProvider,
    onSubmit: @escaping Submission
  ) {
    present(
      .deviceName,
      draft: name,
      radioID: radioID,
      rootNavigationGeneration: nil,
      scope: scope,
      knownRegions: nil,
      onSubmit: onSubmit
    )
  }

  func presentRegionAdd(
    radioID: UUID?,
    rootNavigationGeneration: Int,
    scope: @escaping ScopeProvider,
    knownRegions: @escaping RegionsProvider,
    onSubmit: @escaping Submission
  ) {
    present(
      .regionAdd,
      draft: "",
      radioID: radioID,
      rootNavigationGeneration: rootNavigationGeneration,
      scope: scope,
      knownRegions: knownRegions,
      onSubmit: onSubmit
    )
  }

  func updateDraft(_ value: String, requestID: UUID) {
    guard request?.id == requestID else { return }
    draft = request?.kind == .deviceName
      ? value.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)
      : value
  }

  func setPresented(_ proposed: Bool, requestID: UUID) {
    guard request?.id == requestID else { return }
    if proposed, admittedRequest(requestID) == nil { return }
    // Alert actions can follow the system's false write, so keep their payload until they finish.
    isPresented = proposed
  }

  @discardableResult
  func confirm(requestID: UUID) -> Task<Void, Never>? {
    guard let request = admittedRequest(requestID) else { return nil }
    if request.kind == .regionAdd,
       let error = RegionNameValidator.validate(draft, existingRegions: knownRegions) {
      validationMessage = validationText(for: error)
      return Task { [weak self] in
        guard let self, self.admittedRequest(requestID) != nil else { return }
        self.isPresented = true
      }
    }
    let value = request.kind == .regionAdd ? draft.trimmingCharacters(in: .whitespaces) : draft
    let submit = onSubmit
    finish()
    submit?(value)
    return nil
  }

  func cancel(requestID: UUID) {
    guard admittedRequest(requestID) != nil else { return }
    finish()
  }

  func reconcile() {
    guard let request else { return }
    _ = admittedRequest(request.id)
  }

  private func present(
    _ kind: Kind,
    draft: String,
    radioID: UUID?,
    rootNavigationGeneration: Int?,
    scope: @escaping ScopeProvider,
    knownRegions: RegionsProvider?,
    onSubmit: @escaping Submission
  ) {
    let currentScope = scope()
    guard let radioID, currentScope.admits(kind, radioID: radioID),
          rootNavigationGeneration == nil || rootNavigationGeneration == currentScope.rootNavigationGeneration else { return }
    finish()
    self.draft = draft
    scopeProvider = scope
    knownRegionsProvider = knownRegions
    self.onSubmit = onSubmit
    request = Request(kind: kind, radioID: radioID, rootNavigationGeneration: currentScope.rootNavigationGeneration)
    isPresented = true
  }

  private func admittedRequest(_ requestID: UUID) -> Request? {
    guard let request, request.id == requestID else { return nil }
    guard let scope, scope.admits(request.kind, radioID: request.radioID),
          request.kind != .regionAdd || scope.rootNavigationGeneration == request.rootNavigationGeneration else {
      finish()
      return nil
    }
    return request
  }

  private func finish() {
    isPresented = false
    request = nil
    draft = ""
    validationMessage = nil
    scopeProvider = nil
    knownRegionsProvider = nil
    onSubmit = nil
  }

  private func validationText(for error: RegionNameValidator.ValidationError) -> String? {
    switch error {
    case .empty: nil
    case .invalidCharacters: L10n.Chats.Chats.ChannelInfo.Region.invalidName
    case let .tooLong(maxBytes): L10n.Chats.Chats.ChannelInfo.Region.nameTooLong(maxBytes)
    case .duplicate: L10n.Chats.Chats.ChannelInfo.Region.duplicate
    }
  }
}
