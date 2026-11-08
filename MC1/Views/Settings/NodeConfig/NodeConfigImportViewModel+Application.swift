import Foundation
import MC1Services
import OSLog

extension NodeConfigImportViewModel {
  func prepareConfirmation() {
    guard !isPreparingConfirmation, !isApplying, !isParsing else { return }
    if recovery != nil {
      prepareRecoveryConfirmation()
      return
    }
    guard var input = state.input, let service = nodeConfigService,
          let snapshot = connectionManager?.currentConnection,
          connectedDevice?.id == snapshot.device.id else { return }
    let source = ImportSource(snapshot)
    guard input.source == nil || input.source == source else { return }
    input.source = source
    let preview = NodeConfigImportState.Preview(input: input, source: source, sections: sections)
    state = .preparing(preview)
    showConfirmation = false
    errorMessage = nil
    previewTask = Task {
      defer {
        if case let .preparing(current) = state, current.id == preview.id { state = .preview(input) }
      }
      do {
        let result = try await service.previewImport(input.config, sections: preview.sections)
        guard !Task.isCancelled, case let .preparing(current) = state, current.id == preview.id,
              sections == preview.sections,
              connectionManager?.isCurrent(generation: source.generation, requestID: source.requestID) == true else { return }
        var confirmed = preview
        confirmed.input.channelsWouldOverwrite = result.channelsOverwriteExisting
        state = .confirming(.config(confirmed))
        showConfirmation = true
      } catch is CancellationError {
        return
      } catch {
        guard case let .preparing(current) = state, current.id == preview.id else { return }
        errorMessage = error.userFacingMessage
        logger.error("Import preview failed: \(error.localizedDescription)")
      }
    }
  }

  func cancelConfirmation() {
    if case .confirming = state { state = state.editable }
    showConfirmation = false
  }

  func applyConfig() {
    guard let confirmation else { return }
    cancelConfirmation()
    switch confirmation {
    case let .recovery(summary, input, key):
      applyIdentityRecovery(summary, input: input, privateKey: key)
    case let .config(preview):
      guard inputID == preview.input.id, sections == preview.sections,
            connectionManager?.isCurrent(generation: preview.source.generation, requestID: preview.source.requestID) == true,
            let service = nodeConfigService else { return }
      applyConfiguration(confirmation, preview: preview, service: service)
    }
  }

  private func applyConfiguration(
    _ confirmation: NodeConfigImportState.Confirmation, preview: NodeConfigImportState.Preview, service: NodeConfigService
  ) {
    let application = NodeConfigImportState.Application(confirmation: confirmation)
    state = .applying(application)
    errorMessage = nil
    importTask = Task {
      let (progressStream, progressContinuation) = AsyncStream.makeStream(of: ImportProgress.self)
      let consumer = Task { @MainActor in
        for await progress in progressStream {
          guard case var .applying(current) = state, current.id == application.id else { continue }
          current.progress = progress
          state = .applying(current)
        }
      }
      do {
        try await service.importConfig(preview.input.config, sections: preview.sections, radioID: preview.source.radioID) {
          progressContinuation.yield($0)
        }
        progressContinuation.finish()
        await consumer.value
        if connectedDevice?.id == preview.source.deviceID, let settingsService {
          try? await settingsService.refreshDeviceInfo()
        }
        guard case let .applying(current) = state, current.id == application.id else { return }
        state = .completed(current)
        try? await Task.sleep(for: Timing.resultDisplay)
        guard !Task.isCancelled, case let .completed(current) = state, current.id == application.id else { return }
        resetToFileSelection()
      } catch {
        progressContinuation.finish()
        await consumer.value
        guard case let .applying(current) = state, current.id == application.id else { return }
        state = .failed(current)
        if let interrupted = error as? IdentityImportRecoveryRequired {
          retainInterruptedInput(interrupted.recovery, application: current)
        } else if error is CancellationError {
          errorMessage = Self.cancellationMessage(didApplyAnyWrite: didApplyAnyWrite)
        } else {
          errorMessage = Self.failureMessage(for: error, didApplyAnyWrite: didApplyAnyWrite)
          logger.error("Import failed: \(error.localizedDescription)")
        }
      }
    }
  }

  func cancelImport() {
    importTask?.cancel()
  }

  private enum Timing {
    static let resultDisplay: Duration = .seconds(1.5)
  }
}
