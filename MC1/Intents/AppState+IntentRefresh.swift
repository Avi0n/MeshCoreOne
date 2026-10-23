import AppIntents
import MC1Services

/// True when the captured radio is still current, the re-read matches that set,
/// and the set differs from the last one sent. Nil previous differs from every set, and a missing radio is false.
func shouldPublishRecipientIDs(
  previous: Set<String>?,
  capturedRadioID: UUID?,
  captured: Set<String>,
  currentRadioID: UUID?,
  fresh: Set<String>
) -> Bool {
  guard let capturedRadioID, capturedRadioID == currentRadioID else { return false }
  guard fresh == captured else { return false }
  return captured != previous
}

extension AppState {
  /// Asks Shortcuts to reload when `shouldPublishRecipientIDs` accepts a re-read
  /// of the current radio. A thrown fetch skips the call and leaves the stored set unchanged.
  func publishShortcutRecipientsIfChanged(expectedServices: ServiceContainer?) async {
    guard let capturedRadioID = currentRadioID else { return }
    let store = connectionManager.persistenceStore
    let captured: Set<String>
    do {
      captured = try await loadOfferedRecipientIDs(store: store, radioID: capturedRadioID)
    } catch {
      return
    }

    let radioIDNow = currentRadioID
    #if DEBUG
      beforeShortcutRecipientRereadForTesting?()
    #endif

    let fresh: Set<String>
    if let radioIDNow {
      do {
        fresh = try await loadOfferedRecipientIDs(store: store, radioID: radioIDNow)
      } catch {
        return
      }
    } else {
      fresh = []
    }

    guard !Task.isCancelled, connectionManager.services === expectedServices else { return }
    guard shouldPublishRecipientIDs(
      previous: lastPublishedRecipientIDs,
      capturedRadioID: capturedRadioID,
      captured: captured,
      currentRadioID: currentRadioID,
      fresh: fresh
    ) else { return }

    updateShortcutParameters()
    lastPublishedRecipientIDs = captured
  }

  /// Cleans up stale nodes, then publishes when contacts and channels were both clean.
  /// An ineligible sync still cleans up. A cleanup task publishes only after
  /// `removeStaleNodes` returns and only while its services object is still current.
  func handleDeviceSynced(hasCleanRecipientData: Bool) async {
    let services = connectionManager.services
    let startedCleanup = performStaleNodeCleanup(publishRecipients: hasCleanRecipientData)
    if startedCleanup { return }

    if let services,
       let task = staleNodeCleanupTask,
       staleNodeCleanupServiceID == ObjectIdentifier(services) {
      #if DEBUG
        beforeShortcutRecipientCleanupAwaitForTesting?()
      #endif
      await task.value
      guard hasCleanRecipientData, connectionManager.services === services else { return }
      await publishShortcutRecipientsIfChanged(expectedServices: services)
      return
    }

    guard hasCleanRecipientData else { return }
    await publishShortcutRecipientsIfChanged(expectedServices: services)
  }

  private func loadOfferedRecipientIDs(store: PersistenceStore, radioID: UUID) async throws -> Set<String> {
    let contacts = try await store.fetchContacts(radioID: radioID)
    let channels = try await store.fetchChannels(radioID: radioID)
    return offeredRecipientIDs(contacts: contacts, channels: channels)
  }

  private func updateShortcutParameters() {
    #if DEBUG
      if let updateShortcutParametersForTesting {
        updateShortcutParametersForTesting()
        return
      }
    #endif
    MC1AppShortcutsProvider.updateAppShortcutParameters()
  }
}
