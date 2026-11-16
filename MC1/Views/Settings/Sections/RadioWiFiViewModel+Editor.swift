import Foundation
import MC1Services

extension RadioWiFiViewModel {
  func ensureEditor() {
    guard editor == nil else { return }
    editor = Editor(id: UUID(), ssid: networkName ?? "")
  }

  func openEditor() {
    guard canEditNetwork else { return }
    ensureEditor()
    setEditorPresented(true)
  }

  func closeEditor() async {
    let editorID = editor?.id
    let owner = context
    editor = nil
    confirmation = nil
    setEditorPresented(false)
    if let editorID, let owner, matches(owner), let settings = settingsService {
      await settings.closeRadioWiFiEditor(editorID: editorID)
    }
    await drainOwedRead()
  }

  func loadEditor() async {
    guard !isBusy else { return }
    adoptCurrentContext()
    guard let owner = context, matches(owner), let settings = settingsService else { return }
    ensureEditor()
    guard let editorID = editor?.id else { return }
    let id = UUID()
    operation = .editor(id, owner, editorID)
    editor?.baseline = nil
    do {
      let baseline = try await settings.readRadioWiFiCredentials(
        editorID: editorID, expectedContextID: owner.wifiContextID
      )
      guard matches(owner), editor?.id == editorID, operation?.id == id else {
        finish(id)
        await drainOwedRead()
        return
      }
      if let baseline {
        editor?.baseline = baseline
        if editor?.ssidEdited == false { editor?.ssid = baseline.ssid }
        if editor?.passwordEdited == false { editor?.password = baseline.password }
        observation.networkName = baseline.ssid.isEmpty ? nil : baseline.ssid
        observation.networkRevision = baseline.revision
        observation.networkNameIsCurrent = true
        errorMessage = nil
      }
    } catch is CancellationError {
      // A cancelled or superseded read cannot confer credential knowledge.
    } catch {
      if matches(owner), editor?.id == editorID { errorMessage = error.userFacingMessage }
    }
    finish(id)
    await drainOwedRead()
  }

  func requestSave() async {
    guard canSave, let owner = context, let editor, let baseline = editor.baseline else { return }
    let submission = Submission(owner: owner, baseline: baseline, ssid: editor.ssid, password: editor.password)
    if currentTransportType == .wifi {
      confirmation = .save(submission)
    } else {
      await performSave(submission)
    }
  }

  func performSave(_ submission: Submission) async {
    guard canRunCommands, matches(submission.owner), editor?.id == submission.baseline.editorID,
          let settings = settingsService else { return }
    let id = UUID()
    operation = .write(id, submission.owner)
    do {
      let receipt = try await settings.saveRadioWiFiCredentials(
        ssid: submission.ssid,
        password: submission.password,
        baseline: submission.baseline,
        editorID: submission.baseline.editorID,
        ownerIsCurrent: { [weak self] in
          guard let self else { return false }
          return self.matches(submission.owner)
            && self.editor?.id == submission.baseline.editorID
            && self.operation?.id == id
        }
      )
      if matches(submission.owner), editor?.id == submission.baseline.editorID {
        if receipt.contextID == submission.owner.wifiContextID, let revision = receipt.revision,
           settings.radioWiFiState.stableRevision() == revision {
          if receipt.ssidAccepted || !receipt.uncertain {
            adoptMutationRevision(from: submission.baseline.revision, to: revision)
          } else {
            if observation.enabledRevision == submission.baseline.revision { observation.enabledRevision = revision }
            observation.networkNameIsCurrent = false
          }
          if receipt.ssidAccepted {
            observation.networkName = submission.ssid
            observation.networkRevision = revision
            observation.networkNameIsCurrent = true
          }
        } else {
          if receipt.ssidAccepted, observation.networkRevision == submission.baseline.revision {
            observation.networkName = submission.ssid
            observation.networkRevision = nil
            observation.networkNameIsCurrent = false
          }
          oweRead(submission.owner)
        }
        editor?.baseline = receipt.updatedBaseline
        if let error = receipt.error {
          if !(error is CancellationError) { errorMessage = error.userFacingMessage }
        } else if receipt.revision != nil {
          await closeEditor()
        }
      }
    } catch is CancellationError {
      if matches(submission.owner), editor?.id == submission.baseline.editorID {
        editor?.baseline = nil
      }
    } catch {
      if matches(submission.owner) { errorMessage = error.userFacingMessage }
    }
    finish(id)
    await drainOwedRead()
  }
}
