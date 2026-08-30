import Foundation
import MeshCore

// MARK: - Debounced Contact Delta Sync

extension AdvertisementService {
  func scheduleDeltaSync() {
    guard deltaSyncTask == nil else { return }
    deltaSyncGeneration &+= 1
    let generation = deltaSyncGeneration
    var fireAt = ContinuousClock.now + advertSyncDebounce
    if let lastDeltaSyncEnd {
      fireAt = max(fireAt, lastDeltaSyncEnd + advertSyncMinInterval)
    }
    deltaSyncTask = Task { [weak self] in
      await self?.runDeltaSync(fireAt: fireAt, generation: generation)
    }
  }

  /// Clears `deltaSyncTask` only when this round still owns the registration.
  /// A cancelled round that resumes after a re-arm must not wipe the new task.
  func finishRound(generation: UInt64) {
    guard deltaSyncGeneration == generation else { return }
    deltaSyncTask = nil
  }

  func runDeltaSync(fireAt: ContinuousClock.Instant, generation: UInt64) async {
    do {
      try await Task.sleep(until: fireAt, clock: .continuous)
    } catch {
      finishRound(generation: generation)
      return
    }

    guard !Task.isCancelled, let handler = deltaSyncHandler else {
      finishRound(generation: generation)
      return
    }
    guard !isSyncingContacts else {
      finishRound(generation: generation)
      return
    }

    // Empty-round guard before drain so a schedule with no work never clears a
    // pending 0x8F set or spends a radio round. Must run before
    // `contactsDeletedDuringSync.removeAll()`.
    guard hasPendingDeltaSyncWork else {
      finishRound(generation: generation)
      return
    }

    // Capture before teardown clears `currentRadioID` so rollback can still run
    // after `stopEventMonitoring`.
    let radioID = currentRadioID

    let drained = pendingAdvertKeys
    pendingAdvertKeys.removeAll()
    let drainedReceiveTimes = takeAdvertReceiveTimes(for: drained)
    let drainedPathKeys = pendingPathKeys
    pendingPathKeys.removeAll()
    let fullRefetch = escalateToFullRefetch
    escalateToFullRefetch = false
    // Clear before the commit so only mid-round deletes race this round's write path.
    contactsDeletedDuringSync.removeAll()

    // Pre-round key set gates .newContactDiscovered and escalation only.
    // A failed read must not invent notifications (prefer miss over false
    // notify). Adoption is separate: it uses every drained key that has a row
    // after the exchange so a snapshot failure cannot leave DMs orphaned.
    let preRoundKnownKeys: Set<Data>?
    if let radioID {
      do {
        preRoundKnownKeys = try await dataStore.fetchContactPublicKeys(radioID: radioID)
      } catch {
        logger.error(
          "Contact key snapshot failed; suppressing new-contact notifications this round: \(error.localizedDescription)"
        )
        preRoundKnownKeys = nil
      }
    } else {
      preRoundKnownKeys = nil
    }
    let unknownAtStart: Set<Data> = if let preRoundKnownKeys {
      drained.subtracting(preRoundKnownKeys)
    } else {
      []
    }

    // Snapshot lastModified for known path keys so a successful incremental
    // that returns nothing (radio lastmod ≤ watermark) is detectable.
    let preRoundPathLastModified = await snapshotPathLastModified(
      keys: drainedPathKeys, radioID: radioID
    )

    let outcome = await handler(fullRefetch)

    // Rollback before the teardown guard: a failed or cancelled round may already
    // have committed early batches, and an incremental sync never prunes them.
    await rollBackContactsDeletedDuringSync(radioID: radioID)

    guard !Task.isCancelled, deltaSyncHandler != nil else {
      finishRound(generation: generation)
      return
    }

    switch outcome {
    case .busy:
      // The radio was never asked, so this round keeps the failure budget intact.
      // Do not stamp lastDeltaSyncEnd: a busy poll must not inherit the full min
      // interval. A short busy-specific backoff re-arms below.
      logger.info("Advert delta sync deferred: another sync holds the claim")
      finishRound(generation: generation)
      requeueDrainedWork(
        drained, pathKeys: drainedPathKeys, fullRefetch: fullRefetch, shouldSchedule: false
      )
      scheduleBusyRetry()
      return

    case .notReady:
      // Permanent until a full contact fetch succeeds. Neither requeue nor spend budget.
      logger.notice(
        "Advert delta sync not ready: dropping \(drained.count) pending key(s) until a full contact fetch completes"
      )
      finishRound(generation: generation)
      return

    case .failed:
      consecutiveDeltaSyncFailures += 1
      lastDeltaSyncEnd = .now
      finishRound(generation: generation)

      guard consecutiveDeltaSyncFailures < Self.maxConsecutiveDeltaSyncFailures else {
        // Drop the drained work and wait for new adverts rather than retry an
        // exchange the handler cannot complete.
        //
        // Path keys and escalateToFullRefetch were consumed into locals above;
        // restore them so a pending path update or owed escalation is not
        // silently dropped. Drained advert keys stay dropped — that is the
        // cap's purpose.
        logger.error(
          "Advert delta sync failed \(Self.maxConsecutiveDeltaSyncFailures) times in a row; dropping \(drained.count) pending key(s)"
        )
        consecutiveDeltaSyncFailures = 0
        // A 0x80/0x81 that arrived during this failing round already spent its
        // one scheduleDeltaSync no-op against the still-set task, so no later
        // event re-arms it. Detect that fresh work before restoring the round's
        // own keys and re-arm for it; the min interval keeps it from spinning.
        let freshWorkArrivedMidRound = hasPendingDeltaSyncWork
        for key in drainedPathKeys where !contactsDeletedDuringSync.contains(key) {
          pendingPathKeys.insert(key)
        }
        if fullRefetch {
          escalateToFullRefetch = true
        }
        if freshWorkArrivedMidRound {
          scheduleDeltaSync()
        }
        return
      }

      requeueDrainedWork(drained, pathKeys: drainedPathKeys, fullRefetch: fullRefetch)
      return

    case .synced:
      break
    }

    consecutiveDeltaSyncFailures = 0
    // Stamp phone recency for drained 0x80 keys that now have a row. Unknown
    // contacts get lastHeard = 0 from Contact(radioID:from:); without this a
    // stale radio lastModified makes matchesStaleNodePrune true right after hear.
    await stampAdvertReceiveTimes(drainedReceiveTimes, radioID: radioID)
    // Only keys proven absent before the round are announced as new.
    let insertedKeysForNotify: Set<Data> = if let preRoundKnownKeys {
      drained.subtracting(preRoundKnownKeys)
    } else {
      []
    }
    await reconcile(drained, insertedKeys: insertedKeysForNotify)
    // Adopt against every drained key that now has a row — not only
    // insertedKeysForNotify — so a snapshot failure still links orphan DMs.
    let adoptedContactIDs = await adoptOrphanedMessages(for: drained, radioID: radioID)
    eventBroadcaster.yield(.contactUpdated)
    // A non-nil lastMessageDate after adoption is a new/updated conversation
    // row; contactsVersion alone does not reload the mounted chat list. The
    // adopted DMs never notified at receipt (no contact then), so hand the
    // contacts to the NotificationService owner for the banner and badge.
    if !adoptedContactIDs.isEmpty {
      eventBroadcaster.yield(.conversationsChanged)
      eventBroadcaster.yield(.orphanDirectMessagesAdopted(contactIDs: adoptedContactIDs))
    }

    if !fullRefetch {
      await escalateMissingUnknownKeys(unknownAtStart: unknownAtStart)
      await escalateUndeliveredPathUpdates(
        drainedPathKeys: drainedPathKeys,
        preRoundLastModified: preRoundPathLastModified
      )
    } else {
      await dropStillMissingUnknownKeys(unknownAtStart: unknownAtStart)
    }

    lastDeltaSyncEnd = .now
    finishRound(generation: generation)
    if hasPendingDeltaSyncWork {
      scheduleDeltaSync()
    }
  }

  /// Re-arms after a busy outcome with a short backoff so performResync-style
  /// claim holds do not spin at zero interval.
  private func scheduleBusyRetry() {
    guard deltaSyncTask == nil else { return }
    guard hasPendingDeltaSyncWork else { return }
    deltaSyncGeneration &+= 1
    let generation = deltaSyncGeneration
    let fireAt = ContinuousClock.now + advertSyncBusyBackoff
    deltaSyncTask = Task { [weak self] in
      await self?.runDeltaSync(fireAt: fireAt, generation: generation)
    }
  }

  /// Returns a round's drained work to the pending set and re-arms the timer.
  /// A rolled-back key stays dropped: refetching cannot bring it back.
  /// Pass `shouldSchedule: false` when the caller re-arms with a different backoff.
  private func requeueDrainedWork(
    _ drained: Set<Data>,
    pathKeys: Set<Data>,
    fullRefetch: Bool,
    shouldSchedule: Bool = true
  ) {
    // Keep a consumed full-refetch flag so a flaky escalated round-trip
    // retries as full refetch rather than incremental.
    if fullRefetch {
      escalateToFullRefetch = true
    }
    for key in drained where !contactsDeletedDuringSync.contains(key) {
      recordPendingAdvertKey(key)
    }
    for key in pathKeys where !contactsDeletedDuringSync.contains(key) {
      pendingPathKeys.insert(key)
    }
    if shouldSchedule, hasPendingDeltaSyncWork {
      scheduleDeltaSync()
    }
  }

  /// Removes contacts the radio deleted (0x8F) since this round drained its keys.
  /// Runs before `reconcile` so a batch re-save cannot leave a Discover row for a
  /// deleted contact. The keys stay recorded: reconcile and escalation read them.
  ///
  /// A key that re-advertised after the delete never reaches here: the radio
  /// re-added it, so `recordPendingAdvertKey` clears the tombstone and the
  /// re-created row is legitimate. A later delete re-arms the tombstone, so the
  /// last event the radio sent always decides.
  ///
  /// Uses `deleteContactIfUnreferenced` so a concurrent DM attached after the
  /// batch re-save is kept. Prefer an orphan contact over a cascade wipe.
  private func rollBackContactsDeletedDuringSync(radioID: UUID?) async {
    let keys = contactsDeletedDuringSync
    guard let radioID, !keys.isEmpty else { return }

    for publicKey in keys {
      let pubKeyHex = publicKey.uppercaseHexString()
      do {
        guard let contact = try await dataStore.fetchContact(
          radioID: radioID, publicKey: publicKey
        ) else { continue }
        try await dataStore.deleteContactIfUnreferenced(id: contact.id)
        logger.info("Delta sync rollback: removed contact \(pubKeyHex) deleted by the radio mid-sync")
        eventBroadcaster.yield(.contactUpdated)
      } catch {
        logger.error("Delta sync rollback failed for \(pubKeyHex): \(error.localizedDescription)")
      }
    }
  }

  /// Refreshes Discover rows for drained 0x80 keys and emits new-contact
  /// notifications only for keys this round actually inserted.
  func reconcile(_ drained: Set<Data>, insertedKeys: Set<Data>) async {
    guard let radioID = currentRadioID else { return }

    for publicKey in drained {
      guard !contactsDeletedDuringSync.contains(publicKey) else { continue }
      let pubKeyHex = publicKey.uppercaseHexString()
      do {
        guard let contact = try await dataStore.fetchContact(
          radioID: radioID, publicKey: publicKey
        ) else {
          discoverTrace.info("B2 reconcile: no contact yet key=\(pubKeyHex)")
          continue
        }
        // Re-check after each store hop; the radio can delete mid-round.
        guard !contactsDeletedDuringSync.contains(publicKey) else { continue }

        // Use stored radio fields verbatim; do not stamp phone clock into
        // lastAdvertTimestamp / lastModified (those remain radio-sourced).
        let frame = ContactFrame(
          publicKey: contact.publicKey,
          type: contact.type,
          flags: contact.flags,
          outPathLength: contact.outPathLength,
          outPath: contact.outPath,
          name: contact.name,
          lastAdvertTimestamp: contact.lastAdvertTimestamp,
          latitude: contact.latitude,
          longitude: contact.longitude,
          lastModified: contact.lastModified
        )

        do {
          let (_, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)
          discoverTrace.info("B2 reconcile upsert key=\(pubKeyHex) isNew=\(isNew)")
        } catch {
          discoverTrace.error("B2 reconcile upsert FAILED key=\(pubKeyHex): \(error.localizedDescription)")
        }
        guard !contactsDeletedDuringSync.contains(publicKey) else { continue }

        // Prefer losing a notification over inventing one: only keys absent from
        // the pre-round snapshot and present after the exchange are announced.
        if insertedKeys.contains(publicKey) {
          eventBroadcaster.yield(.newContactDiscovered(
            name: contact.name,
            contactID: contact.id,
            contactType: contact.type
          ))
          logOverwriteReplacementIfRecent(
            newContactName: contact.name,
            newContactType: contact.type
          )
        }
      } catch {
        logger.error("Reconcile failed for \(pubKeyHex): \(error.localizedDescription)")
      }
    }
  }

  /// Links orphaned DMs whose sender prefix matches contacts among `keys`.
  /// `keys` is the drained set for this round (not only newly inserted keys) so
  /// a failed pre-round snapshot cannot disable adoption. Springboard badge is
  /// not updated here; the NotificationService owner refreshes it and posts the
  /// banner from `.orphanDirectMessagesAdopted`.
  ///
  /// Returns the contact ids that adopted at least one message, so the caller
  /// can emit `.conversationsChanged` and `.orphanDirectMessagesAdopted`.
  private func adoptOrphanedMessages(for keys: Set<Data>, radioID: UUID?) async -> [UUID] {
    guard let radioID, !keys.isEmpty else { return [] }

    var contacts: [(id: UUID, publicKey: Data)] = []
    contacts.reserveCapacity(keys.count)
    for publicKey in keys {
      guard !contactsDeletedDuringSync.contains(publicKey) else { continue }
      do {
        if let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) {
          contacts.append((contact.id, contact.publicKey))
        }
      } catch {
        let pubKeyHex = publicKey.uppercaseHexString()
        logger.error("Adoption contact lookup failed for \(pubKeyHex): \(error.localizedDescription)")
      }
    }
    guard !contacts.isEmpty else { return [] }

    do {
      let adopted = try await dataStore.adoptOrphanedDirectMessages(
        radioID: radioID, contacts: contacts
      )
      if !adopted.isEmpty {
        logger.info("Adopted orphaned DMs for \(adopted.count) contact(s)")
      }
      return Array(adopted.keys)
    } catch {
      logger.error("Orphaned DM adoption failed: \(error.localizedDescription)")
      return []
    }
  }

  /// After a successful incremental sync, escalate once when keys that were
  /// unknown at round start still lack a row. Keys present in the pre-round
  /// snapshot stay out of scope (known contacts, including local deletes).
  private func escalateMissingUnknownKeys(unknownAtStart: Set<Data>) async {
    guard let radioID = currentRadioID else { return }

    var missing: Set<Data> = []
    for publicKey in unknownAtStart {
      // A key the radio deleted is missing on purpose; refetching cannot bring it back.
      guard !contactsDeletedDuringSync.contains(publicKey) else { continue }
      guard let exists = await contactExists(radioID: radioID, publicKey: publicKey) else { continue }
      if !exists {
        missing.insert(publicKey)
      }
    }
    guard !missing.isEmpty else { return }

    logger.info("Advert delta sync escalation: \(missing.count) unknown key(s) still missing")
    escalateToFullRefetch = true
    for key in missing {
      recordPendingAdvertKey(key)
    }
  }

  /// Pre-round `lastModified` for known path keys. Keys with no local row are
  /// omitted; post-round absence still counts as undelivered.
  private func snapshotPathLastModified(
    keys: Set<Data>, radioID: UUID?
  ) async -> [Data: UInt32] {
    guard let radioID, !keys.isEmpty else { return [:] }
    var snapshot: [Data: UInt32] = [:]
    snapshot.reserveCapacity(keys.count)
    for publicKey in keys {
      do {
        if let contact = try await dataStore.fetchContact(
          radioID: radioID, publicKey: publicKey
        ) {
          snapshot[publicKey] = contact.lastModified
        }
      } catch {
        let pubKeyHex = publicKey.uppercaseHexString()
        logger.error(
          "Path lastModified snapshot failed for \(pubKeyHex): \(error.localizedDescription)"
        )
      }
    }
    return snapshot
  }

  /// After a successful incremental sync driven by 0x81, escalate once when a
  /// path key was not refreshed. Firmware sets `lastmod` on path recv and only
  /// reports contacts with `lastmod > since`, so a radio RTC reset or phone
  /// clock step-back that lands the new lastmod at or below the stored
  /// watermark returns an empty incremental stream. Known contacts never enter
  /// `escalateMissingUnknownKeys`, so without this check out-path and
  /// coordinates stay stale. One prune-free full refetch recovers; no per-key
  /// `getContact` loop.
  private func escalateUndeliveredPathUpdates(
    drainedPathKeys: Set<Data>,
    preRoundLastModified: [Data: UInt32]
  ) async {
    guard let radioID = currentRadioID, !drainedPathKeys.isEmpty else { return }

    var undelivered = 0
    for publicKey in drainedPathKeys {
      guard !contactsDeletedDuringSync.contains(publicKey) else { continue }
      do {
        guard let contact = try await dataStore.fetchContact(
          radioID: radioID, publicKey: publicKey
        ) else {
          // Path key with no local row and no insert this round.
          undelivered += 1
          continue
        }
        if let before = preRoundLastModified[publicKey], contact.lastModified <= before {
          undelivered += 1
        }
      } catch {
        let pubKeyHex = publicKey.uppercaseHexString()
        logger.error(
          "Path delivery check failed for \(pubKeyHex): \(error.localizedDescription)"
        )
      }
    }
    guard undelivered > 0 else { return }

    logger.info(
      "Advert path update escalation: \(undelivered) path key(s) not refreshed by incremental fetch"
    )
    escalateToFullRefetch = true
  }

  /// After an escalated full refetch, log keys that are still missing (no loop).
  private func dropStillMissingUnknownKeys(unknownAtStart: Set<Data>) async {
    guard let radioID = currentRadioID else { return }

    for publicKey in unknownAtStart {
      guard !contactsDeletedDuringSync.contains(publicKey) else { continue }
      guard let exists = await contactExists(radioID: radioID, publicKey: publicKey) else { continue }
      if !exists {
        let pubKeyHex = publicKey.uppercaseHexString()
        logger.info("Advert full refetch still missing key=\(pubKeyHex); dropping")
      }
    }
  }

  /// Whether a Contact row exists locally, or nil when the store read failed.
  /// Nil is not evidence the radio lacks the contact; callers must not escalate on nil.
  private func contactExists(radioID: UUID, publicKey: Data) async -> Bool? {
    do {
      return try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) != nil
    } catch {
      let pubKeyHex = publicKey.uppercaseHexString()
      logger.error("Contact lookup failed for \(pubKeyHex): \(error.localizedDescription)")
      return nil
    }
  }
}
