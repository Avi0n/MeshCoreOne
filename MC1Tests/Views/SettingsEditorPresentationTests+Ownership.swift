import Foundation
@testable import MC1
import Testing

extension SettingsEditorPresentationTests {
  enum Finish: CaseIterable {
    case cancel
    case submit
    case logicalExit
    case replacement
  }

  @Test(arguments: [SettingsEditorPresentation.Kind.deviceName, .regionAdd])
  func `obsolete actions cannot change A replacement`(kind: SettingsEditorPresentation.Kind) throws {
    let fixture = Fixture(kind: kind)
    let oldRequest = try fixture.present(kind)
    let currentRequest = try fixture.present(kind)
    fixture.presentation.updateDraft("Current", requestID: currentRequest.id)

    fixture.presentation.setPresented(false, requestID: oldRequest.id)
    fixture.presentation.updateDraft("Obsolete", requestID: oldRequest.id)
    fixture.presentation.confirm(requestID: oldRequest.id)
    fixture.presentation.cancel(requestID: oldRequest.id)
    fixture.presentation.setPresented(true, requestID: oldRequest.id)
    #expect(fixture.presentation.isPresented)
    #expect(fixture.presentation.draft == "Current")
    #expect(fixture.context.submitted.isEmpty)

    fixture.presentation.confirm(requestID: currentRequest.id)
    #expect(fixture.context.submitted == ["Current"])
  }

  @Test(arguments: [SettingsEditorPresentation.Kind.deviceName, .regionAdd])
  func `denied old radio presentation leaves the current editor`(kind: SettingsEditorPresentation.Kind) throws {
    let fixture = Fixture(kind: kind)
    let context = fixture.context
    let oldRadioID = context.radioID
    let generation = context.navigation.settingsRootNavigationGeneration
    _ = try fixture.present(kind)
    context.radioID = UUID()
    let current = try fixture.present(kind)
    fixture.presentation.updateDraft("Current", requestID: current.id)

    switch kind {
    case .deviceName:
      fixture.presentation.presentDeviceName(
        "Obsolete", radioID: oldRadioID, scope: { context.scope() },
        onSubmit: { context.submitted.append("Obsolete-" + $0) }
      )
    case .regionAdd:
      fixture.presentation.presentRegionAdd(
        radioID: oldRadioID, rootNavigationGeneration: generation,
        scope: { context.scope() }, knownRegions: { context.knownRegions },
        onSubmit: { context.submitted.append("Obsolete-" + $0) }
      )
    }
    #expect(fixture.presentation.isPresented)
    #expect(fixture.presentation.draft == "Current")
    fixture.presentation.confirm(requestID: current.id)
    #expect(context.submitted == ["Current"])
  }

  @Test
  func `denied old generation presentation leaves the current regions editor`() throws {
    let fixture = Fixture(kind: .regionAdd)
    let context = fixture.context
    let oldGeneration = context.navigation.settingsRootNavigationGeneration
    _ = try fixture.present(.regionAdd)
    context.navigation.navigateToSetting(.advanced)
    let current = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("Current", requestID: current.id)

    fixture.presentation.presentRegionAdd(
      radioID: context.radioID, rootNavigationGeneration: oldGeneration,
      scope: { context.scope() }, knownRegions: { context.knownRegions },
      onSubmit: { context.submitted.append("Obsolete-" + $0) }
    )
    #expect(fixture.presentation.draft == "Current")
    fixture.presentation.confirm(requestID: current.id)
    #expect(context.submitted == ["Current"])
  }

  @Test(arguments: Finish.allCases)
  func `finishing releases captured callback owners`(finish: Finish) throws {
    let fixture = Fixture(kind: .regionAdd)
    let presentation = fixture.presentation
    weak var retainedOwner: CallbackOwner?
    do {
      let owner = CallbackOwner(context: fixture.context)
      retainedOwner = owner
      presentation.presentRegionAdd(
        radioID: owner.context.radioID,
        rootNavigationGeneration: owner.context.navigation.settingsRootNavigationGeneration,
        scope: { owner.context.scope() }, knownRegions: { owner.regions },
        onSubmit: { owner.submitted.append($0) }
      )
    }
    try #require(retainedOwner != nil)
    let request = try #require(presentation.request)
    presentation.updateDraft("South", requestID: request.id)

    switch finish {
    case .cancel:
      presentation.cancel(requestID: request.id)
    case .submit:
      presentation.confirm(requestID: request.id)
    case .logicalExit:
      fixture.context.navigation.selectedSetting = nil
      presentation.reconcile()
    case .replacement:
      _ = try fixture.present(.regionAdd)
    }
    #expect(retainedOwner == nil)
  }

  @Test
  func `submission can open A new editor without old cleanup closing it`() throws {
    let fixture = Fixture(kind: .deviceName)
    let presentation = fixture.presentation
    let context = fixture.context
    presentation.presentDeviceName("First", radioID: context.radioID, scope: { context.scope() }) { value in
      context.submitted.append(value)
      presentation.presentDeviceName(
        "Second", radioID: context.radioID, scope: { context.scope() },
        onSubmit: { context.submitted.append($0) }
      )
    }
    let first = try #require(presentation.request)
    presentation.confirm(requestID: first.id)
    #expect(presentation.isPresented)
    #expect(presentation.draft == "Second")
    let second = try #require(presentation.request)
    presentation.confirm(requestID: second.id)
    #expect(context.submitted == ["First", "Second"])
    #expect(presentation.request == nil)
  }

  @Test
  func `validation retry cannot reopen or submit A replacement`() async throws {
    let fixture = Fixture(kind: .regionAdd)
    let old = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("Invalid!", requestID: old.id)
    fixture.presentation.setPresented(false, requestID: old.id)
    let retry = try #require(fixture.presentation.confirm(requestID: old.id))
    let current = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("Current", requestID: current.id)
    fixture.presentation.setPresented(false, requestID: current.id)

    await retry.value
    #expect(fixture.presentation.isPresented == false)
    #expect(fixture.presentation.draft == "Current")
    #expect(fixture.presentation.validationMessage == nil)
    #expect(fixture.context.submitted.isEmpty)
    fixture.presentation.confirm(requestID: current.id)
    #expect(fixture.context.submitted == ["Current"])
  }

  @Test
  func `cancel after invalid validation releases all captured providers`() async throws {
    let fixture = Fixture(kind: .regionAdd)
    let presentation = fixture.presentation
    weak var retainedOwner: CallbackOwner?
    do {
      let owner = CallbackOwner(context: fixture.context)
      retainedOwner = owner
      presentation.presentRegionAdd(
        radioID: owner.context.radioID,
        rootNavigationGeneration: owner.context.navigation.settingsRootNavigationGeneration,
        scope: { owner.context.scope() }, knownRegions: { owner.regions },
        onSubmit: { owner.submitted.append($0) }
      )
    }
    try #require(retainedOwner != nil)
    let request = try #require(presentation.request)
    presentation.updateDraft("Invalid!", requestID: request.id)
    presentation.setPresented(false, requestID: request.id)
    let retry = try #require(presentation.confirm(requestID: request.id))
    presentation.cancel(requestID: request.id)

    await retry.value
    #expect(retainedOwner == nil)
    #expect(presentation.request == nil)
    #expect(presentation.isPresented == false)
    #expect(presentation.validationMessage == nil)
  }
}
