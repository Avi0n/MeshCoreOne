import Foundation
import MC1Services

extension RadioWiFiViewModel {
  func noteSessionReadiness() async {
    adoptCurrentContext()
    guard connectionState == .ready, let context, matchesContext(context) else {
      clearAssociation()
      owedRead = nil
      if settingsService == nil {
        editor = nil
        setEditorPresented(false)
        confirmation = nil
      }
      return
    }
    oweRead(context)
    await drainOwedRead()
  }

  func refresh() async {
    await noteSessionReadiness()
  }

  func load() async {
    guard !isBusy else { return }
    await noteSessionReadiness()
  }

  func adoptCurrentContext() {
    guard let radioID = connectedDevice?.radioID, let settingsService else { return }
    let binding = settingsService.radioWiFiBinding
    let current = Context(
      radioID: radioID, serviceID: ObjectIdentifier(settingsService), wifiContextID: binding.contextID
    )
    guard current != context else { return }
    context = current
    enabledContext = nil
    observation = ObservedWiFiState()
    setSupport(.unknown)
    owedRead = nil
    confirmation = nil
    editor = nil
    setEditorPresented(false)
    errorMessage = nil
  }

  func drainOwedRead() async {
    guard !editorOpen, !isBusy, owedRead != nil, connectionState == .ready else { return }
    if let refreshTask {
      await refreshTask.value
      return
    }
    let id = UUID()
    refreshTaskID = id
    let task = Task {
      while !self.editorOpen, !self.isBusy, let debt = self.owedRead, self.matches(debt.owner) {
        let owner = debt.owner
        self.owedRead = nil
        let operationID = UUID()
        self.operation = .snapshot(operationID, owner)
        await self.performSnapshot(owner: owner)
        self.finish(operationID)
      }
      if self.refreshTaskID == id {
        self.refreshTaskID = nil
        self.refreshTask = nil
      }
    }
    refreshTask = task
    await task.value
  }

  func performSnapshot(owner: Context) async {
    guard matches(owner), let settings = settingsService else { return }
    guard connectedDevice?.supportsRadioCLI == true else {
      setSupport(.unknown)
      enabledContext = nil
      return
    }
    do {
      let capability = try await settings.probeRadioWiFiSettings(expectedContextID: owner.wifiContextID)
      guard matches(owner) else { return }
      switch capability {
      case .unavailable:
        setSupport(.unavailable)
        enabledContext = nil
        observation = ObservedWiFiState()
        errorMessage = nil
      case .busy:
        clearAssociation()
      case let .supported(probe):
        guard settings.radioWiFiState.stableRevision() == probe.revision else {
          oweRead(owner)
          return
        }
        setSupport(.supported)
        enabledContext = owner
        observation.enabled = probe.enabled
        observation.enabledRevision = probe.revision
        observation.networkNameIsCurrent = false
        clearAssociation()
        let result = await settings.readRadioWiFiObservation(probe: probe)
        guard matches(owner) else { return }
        guard let result, settings.radioWiFiState.stableRevision() == result.revision else {
          if let revision = settings.radioWiFiState.stableRevision(), revision != probe.revision { oweRead(owner) }
          return
        }
        apply(result)
        if let error = result.error {
          if !(error is CancellationError) { errorMessage = error.userFacingMessage }
        } else {
          errorMessage = nil
        }
      }
    } catch is CancellationError {
      // The owner task survives cancellation of a view waiting for this snapshot.
    } catch {
      guard matches(owner) else { return }
      clearAssociation()
      errorMessage = error.userFacingMessage
    }
  }

  func apply(_ result: RadioWiFiReadResult) {
    guard context?.wifiContextID == result.contextID, context?.radioID == result.radioID else { return }
    observation.enabled = result.enabled
    observation.enabledRevision = result.revision
    if result.networkNameWasRead {
      observation.networkName = result.networkName
      observation.networkRevision = result.revision
      observation.networkNameIsCurrent = true
    }
    if let associated = result.associated { observation.associated = associated }
    if result.ipAddressWasRead { observation.ipAddress = result.ipAddress }
  }

  func clearAssociation() {
    observation.associated = nil
    observation.ipAddress = nil
  }

  func adoptMutationRevision(from baseline: UInt64, to revision: UInt64) {
    if observation.enabledRevision == baseline { observation.enabledRevision = revision }
    if observation.networkRevision == baseline { observation.networkRevision = revision }
  }
}
