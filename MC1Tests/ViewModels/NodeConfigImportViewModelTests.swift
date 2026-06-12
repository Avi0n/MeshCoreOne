import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("NodeConfigImportViewModel error localization")
@MainActor
struct NodeConfigImportViewModelTests {

    @Test("A radio-out-of-range error maps to the localized field label and template")
    func radioFieldMapsToLocalizedTemplate() {
        let message = NodeConfigServiceError.invalidRadioSettings(field: .frequency).userFacingMessage
        #expect(message == L10n.Settings.ConfigImport.Error.radioOutOfRange(
            L10n.Settings.ConfigImport.Field.frequency))
    }

    @Test("A position-coordinate error maps to the localized position template")
    func positionCoordinateMapsToLocalizedTemplate() {
        let message = NodeConfigServiceError.invalidCoordinate(field: .positionLatitude).userFacingMessage
        #expect(message == L10n.Settings.ConfigImport.Error.positionInvalid(
            L10n.Settings.ConfigImport.Field.latitude))
    }

    @Test("A contact-coordinate error maps to the localized contact template with the contact name")
    func contactCoordinateMapsToLocalizedTemplate() {
        let message = NodeConfigServiceError.invalidCoordinate(field: .contactLongitude(name: "C1")).userFacingMessage
        #expect(message == L10n.Settings.ConfigImport.Error.contactCoordinateInvalid(
            "C1", L10n.Settings.ConfigImport.Field.longitude))
    }

    @Test("A non-NodeConfigServiceError falls through to its localizedDescription")
    func nonConfigErrorFallsThrough() {
        let error: any Error = URLError(.timedOut)
        #expect(error.userFacingMessage == error.localizedDescription)
    }

    @Test("An invalid-out-path error maps to the localized template with the contact name")
    func invalidOutPathMapsToLocalizedTemplate() {
        let message = NodeConfigServiceError.invalidOutPath(name: "X").userFacingMessage
        #expect(message == L10n.Settings.ConfigImport.Error.invalidOutPath("X"))
        #expect(message.contains("X"))
    }

    @Test("A contact-capacity error maps to the localized template with the needed and available counts")
    func contactCapacityMapsToLocalizedTemplate() {
        let message = NodeConfigServiceError.contactCapacityExceeded(needed: 3, available: 1).userFacingMessage
        #expect(message == L10n.Settings.ConfigImport.Error.contactCapacityExceeded(3, 1))
    }

    @Test("A cancellation message distinguishes a partial cancel from a clean one")
    func cancellationMessagePicksPartialOrClean() {
        #expect(NodeConfigImportViewModel.cancellationMessage(didApplyAnyWrite: true)
            == L10n.Settings.ConfigImport.cancelledPartial)
        #expect(NodeConfigImportViewModel.cancellationMessage(didApplyAnyWrite: false)
            == L10n.Settings.ConfigImport.cancelled)
    }

    @Test("A failure message wraps the localized description only when a write already landed")
    func failureMessagePicksPartialOrClean() {
        let error = DummyError()
        let clean = error.userFacingMessage
        #expect(NodeConfigImportViewModel.failureMessage(for: error, didApplyAnyWrite: true)
            == L10n.Settings.ConfigImport.failedPartial(clean))
        #expect(NodeConfigImportViewModel.failureMessage(for: error, didApplyAnyWrite: false) == clean)
    }

    @Test("Leaving the screen resets a loaded preview back to the file picker")
    func dismissalResetsLoadedPreview() {
        let viewModel = NodeConfigImportViewModel()
        viewModel.importedConfig = MeshCoreNodeConfig(name: "Node", channels: [])
        viewModel.errorMessage = "stale error"
        viewModel.sections.channels = true
        viewModel.showConfirmation = true

        viewModel.handleDismissal()

        #expect(viewModel.importedConfig == nil)
        #expect(viewModel.errorMessage == nil)
        #expect(viewModel.sections == ConfigSections())
        #expect(!viewModel.showConfirmation)
    }

    @Test("Leaving the screen mid-import preserves the in-flight import state")
    func dismissalPreservesInFlightImport() {
        let viewModel = NodeConfigImportViewModel()
        viewModel.importedConfig = MeshCoreNodeConfig(name: "Node", channels: [])
        viewModel.isApplying = true
        viewModel.applyProgress = 0.5

        viewModel.handleDismissal()

        #expect(viewModel.importedConfig != nil)
        #expect(viewModel.isApplying)
        #expect(viewModel.applyProgress == 0.5)
    }
}

private struct DummyError: Error {}
