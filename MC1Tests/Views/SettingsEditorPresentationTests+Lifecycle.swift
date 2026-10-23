import Foundation
@testable import MC1
import Testing

extension SettingsEditorPresentationTests {
  @Test(arguments: [SettingsEditorPresentation.Kind.deviceName, .regionAdd])
  func `reconciliation closes editors on logical exit`(kind: SettingsEditorPresentation.Kind) throws {
    let fixture = Fixture(kind: kind)
    let request = try fixture.present(kind)
    fixture.presentation.updateDraft("Draft", requestID: request.id)
    Exit.tabLeft.apply(to: fixture.context)
    fixture.presentation.reconcile()

    #expect(fixture.presentation.isPresented == false)
    #expect(fixture.presentation.request == nil)
    #expect(fixture.presentation.draft.isEmpty)
    #expect(fixture.presentation.validationMessage == nil)
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted.isEmpty)
  }

  @Test(arguments: [SettingsEditorPresentation.Kind.deviceName, .regionAdd], Exit.allCases)
  func `confirm reads scope before any reconciliation`(kind: SettingsEditorPresentation.Kind, exit: Exit) throws {
    let fixture = Fixture(kind: kind)
    let request = try fixture.present(kind)
    fixture.presentation.updateDraft("Draft", requestID: request.id)
    exit.apply(to: fixture.context)
    fixture.presentation.confirm(requestID: request.id)

    #expect(fixture.context.submitted.isEmpty)
    #expect(fixture.presentation.request == nil)
    #expect(fixture.presentation.isPresented == false)
  }

  @Test(arguments: [SettingsEditorPresentation.Kind.deviceName, .regionAdd], Exit.allCases)
  func `stale source cannot present after logical exit`(kind: SettingsEditorPresentation.Kind, exit: Exit) {
    let fixture = Fixture(kind: kind)
    let context = fixture.context
    let sourceRadioID = context.radioID
    let sourceGeneration = context.navigation.settingsRootNavigationGeneration
    exit.apply(to: context)

    switch kind {
    case .deviceName:
      fixture.presentation.presentDeviceName(
        "Stale", radioID: sourceRadioID, scope: { context.scope() },
        onSubmit: { context.submitted.append($0) }
      )
    case .regionAdd:
      fixture.presentation.presentRegionAdd(
        radioID: sourceRadioID, rootNavigationGeneration: sourceGeneration,
        scope: { context.scope() }, knownRegions: { context.knownRegions },
        onSubmit: { context.submitted.append($0) }
      )
    }
    #expect(fixture.presentation.request == nil)
    #expect(fixture.presentation.isPresented == false)
    #expect(context.submitted.isEmpty)
  }

  @Test(arguments: [false, true])
  func `regions reject removed descendant or capability before reconciliation`(capabilityLost: Bool) throws {
    let fixture = Fixture(kind: .regionAdd)
    let request = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("North", requestID: request.id)
    if capabilityLost {
      fixture.context.supportsRegions = false
    } else {
      fixture.context.descendants = []
    }
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted.isEmpty)
    #expect(fixture.presentation.request == nil)
  }

  @Test
  func `entering A name descendant retires its editor`() throws {
    let fixture = Fixture(kind: .deviceName)
    let request = try fixture.present(.deviceName)
    fixture.context.descendants = [.publicKey(Data([0x01]))]
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted.isEmpty)
    #expect(fixture.presentation.request == nil)
  }

  @Test
  func `same root name request retains draft before and after reconciliation`() throws {
    let fixture = Fixture(kind: .deviceName)
    let request = try fixture.present(.deviceName)
    fixture.presentation.updateDraft("Unsaved", requestID: request.id)
    fixture.context.navigation.navigateToSetting(.deviceInfo)
    fixture.presentation.reconcile()

    #expect(fixture.presentation.isPresented)
    #expect(fixture.presentation.draft == "Unsaved")
    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted == ["Unsaved"])
  }

  @Test
  func `same root advanced request rejects confirm before path observer runs`() throws {
    let fixture = Fixture(kind: .regionAdd)
    let request = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("North", requestID: request.id)
    fixture.context.navigation.navigateToSetting(.advanced)
    try #require(fixture.context.descendants == [.manageRegions])

    fixture.presentation.confirm(requestID: request.id)
    #expect(fixture.context.submitted.isEmpty)
    #expect(fixture.presentation.request == nil)
  }

  @Test
  func `invalid region retry reads scope before reconciliation`() async throws {
    let fixture = Fixture(kind: .regionAdd)
    let request = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("Invalid!", requestID: request.id)
    fixture.presentation.setPresented(false, requestID: request.id)
    let retry = try #require(fixture.presentation.confirm(requestID: request.id))
    Exit.radioReplaced.apply(to: fixture.context)

    await retry.value
    #expect(fixture.presentation.request == nil)
    #expect(fixture.presentation.isPresented == false)
    #expect(fixture.context.submitted.isEmpty)
  }

  @Test
  func `same root advanced request also rejects pending validation retry`() async throws {
    let fixture = Fixture(kind: .regionAdd)
    let request = try fixture.present(.regionAdd)
    fixture.presentation.updateDraft("Invalid!", requestID: request.id)
    fixture.presentation.setPresented(false, requestID: request.id)
    let retry = try #require(fixture.presentation.confirm(requestID: request.id))
    fixture.context.navigation.navigateToSetting(.advanced)

    await retry.value
    #expect(fixture.presentation.request == nil)
    #expect(fixture.presentation.isPresented == false)
    #expect(fixture.context.submitted.isEmpty)
  }
}
