// SyncCoordinator+Sync.swift
import Foundation

// MARK: - Full Sync & Connection Lifecycle

extension SyncCoordinator {
  private struct ContactChannelSyncResult {
    let contacts: SyncPhaseStatus
    let channels: SyncPhaseStatus
    let channelRetryIndices: [UInt8]
  }

  /// Contact watermark value meaning no contact sync has ever succeeded.
  static let noContactWatermark: UInt32 = 0

  /// `since` value for a prune-free full contact fetch: the device reports every
  /// contact while local rows the device omits are kept.
  private static let pruneFreeFullFetchSince = Date(timeIntervalSince1970: 0)

  /// Seconds the incremental `since` filter is rewound from the stored watermark.
  /// The device reports a contact only when `lastmod > since`, so a contact modified
  /// in the same second as the watermark would never be reported. One second of
  /// overlap re-reports that second; the upsert behind it is idempotent.
  private static let incrementalSinceOverlap: TimeInterval = 1

  /// Max lead over the plausibility reference before a stored watermark is
  /// treated as unusable for incremental `since`. Multi-day residual lastmod
  /// from a radio RTC far ahead of the phone is broken; minute-scale lead is
  /// normal drift.
  static let contactWatermarkPlausibilitySkew: TimeInterval = 2 * 24 * 60 * 60

  /// Bound for waiting out an advert-owned contact-sync claim.
  /// Above `SessionConfiguration.contactStreamHardTimeout` (180s) so a large
  /// contact table can finish streaming, but a wedged wait cannot hang forever.
  static let advertContactSyncWaitTimeout: Duration = .seconds(200)

  /// Developer-facing reason when the advert claim wait hits its bound.
  static let advertContactSyncWaitTimedOutMessage =
    "Timed out waiting for background contact sync"

  /// How to use a stored contact-sync watermark for one fetch round.
  /// Does not rewrite storage — only decides the `since` filter for this round.
  enum ContactWatermarkUse: Equatable, Sendable {
    /// No successful contact sync stamp yet.
    case none
    /// Stamp is usable for incremental `since = watermark - 1`.
    case incremental(UInt32)
    /// Stamp is implausibly ahead of the reference. Fetch with `since == nil`
    /// this round; leave the stored value alone until write-back of the new max.
    case invalid(stored: UInt32)
  }

  /// The `since` filter for an incremental contact fetch from a stored watermark.
  private static func incrementalSince(watermark: UInt32) -> Date {
    Date(timeIntervalSince1970: Double(watermark) - incrementalSinceOverlap)
  }

  /// Decides whether a stored contact-sync watermark can drive incremental sync.
  ///
  /// Reference clock: **phone `Date`**. `syncDeviceTimeIfNeeded` holds the radio
  /// within `deviceClockDriftTolerance` (5 s) of the phone on connect and sync
  /// retry, so the phone is a local proxy for radio time without an extra
  /// `getTime` BLE round on every contact sync and advert delta.
  ///
  /// When the stamp leads the reference by more than
  /// `contactWatermarkPlausibilitySkew`, return `.invalid` so the caller fetches
  /// with `since == nil` once. Never rewrite the stored stamp downward here —
  /// phone-clock clamps pin below radio lastmods and loop full-table deltas.
  nonisolated static func contactWatermarkUse(
    fromLastContactSync raw: UInt32?,
    referenceNow: Date = Date()
  ) -> ContactWatermarkUse {
    guard let raw, raw != noContactWatermark else { return .none }
    let referenceSeconds = UInt32(referenceNow.timeIntervalSince1970)
    let maxSkew = UInt32(contactWatermarkPlausibilitySkew)
    let upperBound = referenceSeconds > UInt32.max - maxSkew
      ? UInt32.max
      : referenceSeconds + maxSkew
    if raw > upperBound {
      return .invalid(stored: raw)
    }
    return .incremental(raw)
  }

  // MARK: - Full Sync

  /// Performs full sync of contacts, channels, and messages from device.
  ///
  /// This is the core sync method that ensures all data is pulled from the device.
  /// It syncs in order: contacts → channels → messages.
  ///
  /// - Parameters:
  ///   - radioID: The connected device UUID
  ///   - dataStore: Persistence store for data operations
  ///   - contactService: Service for contact sync
  ///   - channelService: Service for channel sync
  ///   - messagePollingService: Service for message polling
  ///   - appStateProvider: Optional provider for foreground/background state. When nil,
  ///     defaults to foreground mode (channels sync). When provided and app is backgrounded,
  ///     channel sync is skipped to reduce BLE traffic.
  ///   - rxLogService: Optional service for updating contact public keys after sync.
  ///   - forceFullSync: When true, ignores lastContactSync watermark and fetches all contacts.
  @discardableResult
  func performFullSync(
    radioID: UUID,
    dataStore: any PersistenceStoreProtocol,
    contactService: some ContactServiceProtocol,
    channelService: some ChannelServiceProtocol,
    messagePollingService: some MessagePollingServiceProtocol,
    appStateProvider: AppStateProvider? = nil,
    rxLogService: RxLogService? = nil,
    notificationService: NotificationService? = nil,
    forceFullSync: Bool = false,
    channelSyncConfig: ChannelSyncConfig = .none,
    platformName: String = "unknown"
  ) async throws -> FullSyncResult {
    try await waitForAdvertContactSync()

    // Prevent concurrent syncs — actor-local flag avoids the TOCTOU window
    // that existed when guarding via `await state.isSyncing`
    guard !isSyncInProgress else {
      logger.warning("performFullSync called while already syncing, ignoring duplicate")
      return .skipped
    }
    isSyncInProgress = true
    defer { isSyncInProgress = false }

    return try await runFullSync(
      radioID: radioID,
      dataStore: dataStore,
      contactService: contactService,
      channelService: channelService,
      messagePollingService: messagePollingService,
      appStateProvider: appStateProvider,
      rxLogService: rxLogService,
      notificationService: notificationService,
      forceFullSync: forceFullSync,
      channelSyncConfig: channelSyncConfig,
      platformName: platformName
    )
  }

  /// Full-sync body without the `isSyncInProgress` claim. Callers must hold
  /// the claim already: `performFullSync` takes it per call, while
  /// `onConnectionEstablished` claims it before its handler-wiring awaits so
  /// racing connection setups cannot double-wire.
  private func runFullSync(
    radioID: UUID,
    dataStore: any PersistenceStoreProtocol,
    contactService: some ContactServiceProtocol,
    channelService: some ChannelServiceProtocol,
    messagePollingService: some MessagePollingServiceProtocol,
    appStateProvider: AppStateProvider? = nil,
    rxLogService: RxLogService? = nil,
    notificationService: NotificationService? = nil,
    forceFullSync: Bool = false,
    channelSyncConfig: ChannelSyncConfig = .none,
    platformName: String = "unknown"
  ) async throws -> FullSyncResult {
    logger.info("Starting full sync for device \(radioID)")
    let syncStart = ContinuousClock.now

    do {
      // Set phase before triggering pill visibility
      logger.info("[Sync] State → .syncing(.contacts)")
      await setState(.syncing(progress: SyncProgress(phase: .contacts, current: 0, total: 0)))
      hasEndedSyncActivity = false
      logger.info("[Sync] Calling onSyncActivityStarted")
      await onSyncActivityStarted?()

      // Perform contacts and channels sync (activity should show pill).
      // Contacts remain connection-critical. Channel errors are degraded
      // state: keep existing local channels and let the caller schedule a
      // channel-only retry instead of replaying contacts.
      let contactChannelResult = try await syncContactsAndChannels(
        radioID: radioID,
        dataStore: dataStore,
        contactService: contactService,
        channelService: channelService,
        appStateProvider: appStateProvider,
        rxLogService: rxLogService,
        forceFullSync: forceFullSync,
        channelSyncSkipWindow: channelSyncConfig.channelSyncSkipWindow,
        lastCleanChannelSync: channelSyncConfig.lastCleanChannelSync,
        lastAttemptedChannelSync: channelSyncConfig.lastAttemptedChannelSync,
        usePipelinedRead: channelSyncConfig.usePipelinedChannelRead
      )

      // End sync activity before messages phase (pill should hide).
      // During resync, the outer bracket (beginResyncActivity) holds syncActivityCount >= 1,
      // so this inner succeeded=true decrement cannot reach zero and prematurely trigger
      // the "Ready" toast. During initial sync there is no outer bracket, and reaching
      // zero here is the correct "sync complete" signal.
      await endSyncActivityOnce(succeeded: true)

      // Phase 3: Messages (no pill for this phase)
      logger.info("[Sync] State → .syncing(.messages)")
      await setState(.syncing(progress: SyncProgress(phase: .messages, current: 0, total: 0)))
      let messageStart = ContinuousClock.now
      let messageCount: Int
      let messageStatus: SyncPhaseStatus
      do {
        messageCount = try await messagePollingService.pollAllMessages()
        messageStatus = .clean
      } catch let error as CancellationError {
        throw error
      } catch {
        messageCount = 0
        messageStatus = .failed(error.localizedDescription)
        logger.warning("[Sync] Message polling failed after contacts/channels: \(error.localizedDescription)")
      }
      let messageElapsed = ContinuousClock.now - messageStart
      logger.info("[Sync] Phase end: messages - \(messageCount) polled in \(messageElapsed)")

      // Clear notification suppression immediately after catch-up poll completes.
      // All catch-up messages have been processed with suppression active;
      // any subsequent event-monitor messages are genuinely new and should notify.
      if let notificationService {
        cancelSuppressionWatchdog()
        await MainActor.run {
          notificationService.isSuppressingNotifications = false
        }
      }

      await notifyConversationsChanged()

      // Complete
      logger.info("[Sync] State → .synced")
      await setState(.synced)
      await setLastSyncDate(Date())

      let elapsed = ContinuousClock.now - syncStart
      logger.info("[Sync] Complete: platform=\(platformName), messages=\(messageCount), duration=\(elapsed)")
      return FullSyncResult(
        contacts: contactChannelResult.contacts,
        channels: contactChannelResult.channels,
        messages: messageStatus,
        channelRetryIndices: contactChannelResult.channelRetryIndices
      )
    } catch let error as CancellationError {
      // Defensive: ensure activity count is decremented even if cancellation
      // occurs outside the contacts/channels error path.
      await endSyncActivityOnce()
      await setState(.idle)
      throw error
    } catch {
      // Defensive: ensure activity count is decremented even if an error is
      // thrown from a path that bypasses the inner contacts/channels catch.
      await endSyncActivityOnce()
      logger.warning("[Sync] State → .failed: \(error.localizedDescription)")
      await setState(.failed(.syncFailed(error.localizedDescription)))
      throw error
    }
  }

  /// Attempts to resync data after a previous sync failure.
  /// Unlike onConnectionEstablished, does not rewire handlers or restart event monitoring.
  /// - Parameters:
  ///   - radioID: The connected device UUID
  ///   - dependencies: The sync dependency surface
  ///   - forceFullSync: When true, forces a full contact sync instead of incremental.
  ///   - channelSyncConfig: Channel sync skip configuration.
  ///   - platformName: Platform name for instrumentation logging.
  /// - Returns: `true` if sync succeeded, `false` if it failed
  func performResync(
    radioID: UUID,
    dependencies: SyncDependencies,
    forceFullSync: Bool = false,
    channelSyncConfig: ChannelSyncConfig = .none,
    platformName: String = "unknown"
  ) async -> Bool {
    #if DEBUG
      if let override = performResyncOverride {
        return await override(radioID, dependencies)
      }
    #endif
    logger.info("Attempting resync for device \(radioID)")

    await MainActor.run {
      logger.info("Suppressing message notifications during resync")
      dependencies.notificationService.isSuppressingNotifications = true
    }
    startSuppressionWatchdog(notificationService: dependencies.notificationService)
    logger.info("[Sync] Pausing auto-fetch for resync")
    await dependencies.messagePollingService.pauseAutoFetch()

    do {
      let result = try await performFullSync(
        radioID: radioID,
        dataStore: dependencies.dataStore,
        contactService: dependencies.contactService,
        channelService: dependencies.channelService,
        messagePollingService: dependencies.messagePollingService,
        appStateProvider: dependencies.appStateProvider,
        rxLogService: dependencies.rxLogService,
        notificationService: dependencies.notificationService,
        forceFullSync: forceFullSync,
        channelSyncConfig: channelSyncConfig,
        platformName: platformName
      )

      startDiscoveryEventMonitoring(dependencies: dependencies, radioID: radioID)

      await drainHandlersAndResumeNotifications(
        notificationService: dependencies.notificationService,
        messagePollingService: dependencies.messagePollingService,
        context: "resync complete"
      )
      logger.info("[Sync] Resuming auto-fetch after resync")
      await dependencies.messagePollingService.resumeAutoFetch()

      if result.isConnectionUsable {
        logger.info("Resync succeeded")
        return true
      }

      logger.warning("Resync completed without usable contacts")
      return false
    } catch {
      await drainHandlersAndResumeNotifications(
        notificationService: dependencies.notificationService,
        messagePollingService: dependencies.messagePollingService,
        context: "resync failed"
      )
      await dependencies.messagePollingService.resumeAutoFetch()

      logger.warning("Resync failed: \(error.localizedDescription)")
      await setState(.failed(.syncFailed(error.localizedDescription)))
      return false
    }
  }

  // MARK: - Connection Lifecycle

  /// Called by ConnectionManager when connection is established.
  /// Wires handlers, starts event monitoring, and performs initial sync.
  ///
  /// This is the critical method that fixes the handler wiring gap:
  /// 1. Wire message handlers first (before events can arrive)
  /// 2. Start event monitoring (handlers are now ready)
  /// 3. Perform full sync (contacts, channels, messages)
  /// 4. Start discovery event monitoring (for ongoing contact discovery)
  ///
  /// - Parameters:
  ///   - radioID: The connected device UUID
  ///   - dependencies: The sync dependency surface
  ///   - forceFullSync: When true, forces a full contact sync instead of incremental.
  ///   - channelSyncConfig: Channel sync skip configuration.
  ///   - platformName: Platform name for instrumentation logging.
  @discardableResult
  func onConnectionEstablished(
    radioID: UUID,
    dependencies: SyncDependencies,
    forceFullSync: Bool = false,
    channelSyncConfig: ChannelSyncConfig = .none,
    platformName: String = "unknown"
  ) async throws -> FullSyncResult {
    logger.info("Connection established for device \(radioID)")

    try await waitForAdvertContactSync()

    // Claim synchronously before the wiring awaits below. A read-only guard
    // let two racing calls (rapid auto-reconnect cycles) both pass and
    // double-wire handlers; the claim makes the loser skip immediately.
    guard !isSyncInProgress else {
      logger.warning("onConnectionEstablished called while already syncing, ignoring duplicate")
      return .skipped
    }
    isSyncInProgress = true
    defer { isSyncInProgress = false }

    // Suppress message notifications during sync to avoid flooding user on reconnect
    // Unread counts and badges still update - only system notifications are suppressed
    await MainActor.run {
      logger.info("Suppressing message notifications during sync")
      dependencies.notificationService.isSuppressingNotifications = true
    }
    startSuppressionWatchdog(notificationService: dependencies.notificationService)

    do {
      // Defer advert-driven contact sync during full sync to avoid BLE contention
      await dependencies.advertisementService.setSyncingContacts(true)

      // 1. Wire message handlers and advert delta-sync first (before events can arrive).
      // Delta-sync wiring must not wait until after runFullSync: if the initial sync
      // throws, performResync never rewires handlers and the handler would stay dead.
      await wireMessageHandlers(dependencies: dependencies, radioID: radioID)
      let dataStore = dependencies.dataStore
      let contactService = dependencies.contactService
      await dependencies.advertisementService.setDeltaSyncHandler { [weak self] fullRefetch in
        // A released coordinator means the connection is gone; retrying cannot help.
        guard let self else { return .failed }
        return await self.performAdvertContactSync(
          fullRefetch: fullRefetch,
          radioID: radioID,
          dataStore: dataStore,
          contactService: contactService
        )
      }

      // Clean up legacy blocked sender messages still in DB from older app versions
      await deleteBlockedSenderMessages(radioID: radioID, dataStore: dependencies.dataStore)

      // 2. Now start event monitoring (handlers are ready), but delay auto-fetch and advert monitoring until after sync
      logger.info("[Sync] Starting event monitoring for device \(radioID.uuidString.prefix(8))")
      await dependencies.startEventMonitoring(radioID, false)

      // 3. Export device private key for direct message decryption
      do {
        let privateKey = try await dependencies.exportPrivateKey()
        await dependencies.rxLogService.updatePrivateKey(privateKey)
        logger.debug("Device private key exported for direct message decryption")
      } catch {
        logger.warning("Failed to export private key: \(error.localizedDescription)")
      }

      // 4. Perform full sync (claim is already held; the guarded entry
      // point would see its own claim and skip)
      let syncResult = try await runFullSync(
        radioID: radioID,
        dataStore: dependencies.dataStore,
        contactService: dependencies.contactService,
        channelService: dependencies.channelService,
        messagePollingService: dependencies.messagePollingService,
        appStateProvider: dependencies.appStateProvider,
        rxLogService: dependencies.rxLogService,
        notificationService: dependencies.notificationService,
        forceFullSync: forceFullSync,
        channelSyncConfig: channelSyncConfig,
        platformName: platformName
      )

      // 5. Start discovery event monitoring (for ongoing contact discovery).
      // Intentionally after the full sync so adverts arriving during sync
      // do not spam notifications.
      startDiscoveryEventMonitoring(dependencies: dependencies, radioID: radioID)

      // 6. Flush deferred advert-driven contact fetches now that discovery monitoring is live
      await dependencies.advertisementService.setSyncingContacts(false)

      // 7. Drain pending message handlers and resume notifications
      await drainHandlersAndResumeNotifications(
        notificationService: dependencies.notificationService,
        messagePollingService: dependencies.messagePollingService,
        context: "sync complete"
      )

      // 8. Start auto-fetch after suppression is cleared to avoid notification spam
      logger.info("[Sync] Starting auto-fetch for device \(radioID.uuidString.prefix(8))")
      await dependencies.messagePollingService.startAutoFetch(radioID: radioID)

      logger.info("Connection setup complete for device \(radioID)")
      return syncResult
    } catch {
      // Drain pending message handlers and resume notifications
      await drainHandlersAndResumeNotifications(
        notificationService: dependencies.notificationService,
        messagePollingService: dependencies.messagePollingService,
        context: "sync failed"
      )
      await dependencies.advertisementService.setSyncingContacts(false)
      throw error
    }
  }

  /// Called when disconnecting from device
  ///
  /// If disconnect occurs mid-sync (during contacts or channels phase), we must call
  /// onSyncActivityEnded to decrement the activity count, otherwise the pill stays stuck.
  func onDisconnected(notificationService: NotificationService) async {
    let currentState = await state
    logger.warning(
      "[Sync] onDisconnected called - syncState: \(String(describing: currentState)), hasEndedSyncActivity: \(hasEndedSyncActivity)"
    )

    // Safety net: clear sync guard flags on disconnect and wake any waiters so
    // pull-to-refresh / full-sync cannot stay suspended after the connection drops.
    if isSyncInProgress {
      logger.warning("isSyncInProgress still true at disconnect — clearing as safety net")
    }
    isSyncInProgress = false
    advertContactSyncActive = false
    manualContactSyncActive = false
    resumeAllAdvertSyncWaiters()

    // The next session must prove its own full contact fetch before delta sync runs.
    fullContactSyncCompletedRadioID = nil
    invalidWatermarkRecoveryRadioID = nil
    invalidWatermarkRecoveryExhaustedLoggedRadioID = nil

    // Note: pending reactions are not cleared on disconnect - they persist for the app session
    // This handles temporary BLE disconnects without losing queued reactions
    unresolvedChannelIndices.removeAll()
    lastUnresolvedChannelSummaryAt = nil

    // If we're mid-sync in contacts or channels phase, end the activity to hide the pill
    if case let .syncing(progress) = currentState,
       progress.phase == .contacts || progress.phase == .channels {
      await endSyncActivityOnce()
    }

    logger.info("[Sync] State → .idle (disconnected)")
    await setState(.idle)

    // Safety net: ensure suppression is cleared on disconnect
    // Handles edge cases like connection dropping mid-sync or force-quit
    cancelSuppressionWatchdog()
    await MainActor.run {
      notificationService.isSuppressingNotifications = false
    }

    logger.info("Disconnected, sync state reset to idle")
  }

  // MARK: - Sync Helpers

  /// Syncs contacts and channels from the device (phases 1 and 2 of full sync).
  private func syncContactsAndChannels(
    radioID: UUID,
    dataStore: any PersistenceStoreProtocol,
    contactService: some ContactServiceProtocol,
    channelService: some ChannelServiceProtocol,
    appStateProvider: AppStateProvider?,
    rxLogService: RxLogService?,
    forceFullSync: Bool,
    channelSyncSkipWindow: Duration = .zero,
    lastCleanChannelSync: Date? = nil,
    lastAttemptedChannelSync: Date? = nil,
    usePipelinedRead: Bool = false
  ) async throws -> ContactChannelSyncResult {
    // Fetch device once for both contacts (lastContactSync) and channels (maxChannels)
    let device = try await dataStore.fetchDevice(radioID: radioID)

    // At capacity, force a pruning contact fetch (`since == nil`). Offline
    // eviction is invisible to the incremental watermark. Keep this contacts-
    // only: do not set `forceFullSync`, which also forces channel sync.
    // Decide here, before the watermark switch, so the one-shot invalid-
    // watermark recovery token is not spent. Exclude the virtual V-contact
    // from the count. Skip when maxContacts is unknown or zero; a count-read
    // failure leaves atCapacity false.
    var atCapacity = false
    var realContactCount = 0
    if let maxContacts = device?.maxContacts, maxContacts > 0 {
      do {
        let keys = try await dataStore.fetchContactPublicKeys(radioID: radioID)
        realContactCount = keys.count
        if let selfPublicKey = device?.publicKey,
           let vContactKey = VContactIdentity.publicKey(forSelfPublicKey: selfPublicKey),
           keys.contains(vContactKey) {
          realContactCount -= 1
        }
        atCapacity = realContactCount >= Int(maxContacts)
      } catch {
        logger.error("Failed to read local contact count for capacity check: \(error)")
      }
    }

    // Phase 1: Contacts (incremental unless forced full or at capacity)
    var ranInvalidWatermarkRecovery = false
    let lastContactSync: Date?
    if forceFullSync || atCapacity {
      lastContactSync = nil
      if forceFullSync {
        logger.notice(
          "[Sync] Phase start: contacts (FULL sync, reason=forceFullSync) — local contacts not on device will be pruned"
        )
      } else {
        let maxContacts = device?.maxContacts ?? 0
        logger.notice(
          "[Sync] Phase start: contacts (FULL sync, reason=at capacity (local \(realContactCount) of max \(maxContacts))) — local contacts not on device will be pruned"
        )
      }
    } else {
      switch Self.contactWatermarkUse(fromLastContactSync: device?.lastContactSync) {
      case .none:
        lastContactSync = nil
        logger.notice(
          "[Sync] Phase start: contacts (FULL sync, reason=no watermark) — local contacts not on device will be pruned"
        )
      case let .incremental(watermark):
        lastContactSync = Self.incrementalSince(watermark: watermark)
      case let .invalid(stored):
        // Connect full-sync may prune (`since == nil`). Advert recovery must not —
        // see performAdvertContactSync. Bound to one recovery per radio lifetime.
        if invalidWatermarkRecoveryRadioID == radioID {
          lastContactSync = Self.incrementalSince(watermark: stored)
          logInvalidWatermarkRecoveryExhaustedIfNeeded(radioID: radioID, stored: stored)
        } else {
          ranInvalidWatermarkRecovery = true
          lastContactSync = nil
          logger.notice(
            "[Sync] Phase start: contacts (FULL sync, reason=invalid watermark \(stored) exceeds phone reference + \(Int(Self.contactWatermarkPlausibilitySkew))s) — one-shot recovery, store not rewritten"
          )
        }
      }
    }

    _ = try await syncContactsPhase(
      radioID: radioID,
      dataStore: dataStore,
      contactService: contactService,
      since: lastContactSync,
      forceFullSync: forceFullSync
    )

    if ranInvalidWatermarkRecovery {
      invalidWatermarkRecoveryRadioID = radioID
    }

    // Update RxLogService with contact public keys for direct message decryption
    if let rxLogService {
      do {
        let publicKeys = try await dataStore.fetchContactPublicKeysByPrefix(radioID: radioID)
        await rxLogService.updateContactPublicKeys(publicKeys)
        logger.debug("Updated \(publicKeys.count) contact public keys for direct message decryption")
      } catch {
        logger.error("Failed to fetch contact public keys: \(error)")
      }
    }

    // Phase 2: Channels (foreground only)
    var channelStatus: SyncPhaseStatus = .skipped
    var channelRetryIndices: [UInt8] = []
    logger.debug("About to check foreground state, provider exists: \(appStateProvider != nil)")
    let shouldSyncChannels: Bool
    if let provider = appStateProvider {
      logger.debug("Calling isInForeground...")
      shouldSyncChannels = await provider.isInForeground
      logger.debug("isInForeground returned: \(shouldSyncChannels)")
    } else {
      logger.debug("No appStateProvider, defaulting to foreground mode")
      shouldSyncChannels = true
    }
    logger.debug("Proceeding with shouldSyncChannels=\(shouldSyncChannels)")
    if shouldSyncChannels {
      let shouldSkipChannels: Bool = {
        guard !forceFullSync,
              channelSyncSkipWindow > .zero else { return false }
        let now = Date()
        if let lastSync = lastCleanChannelSync,
           now.timeIntervalSince(lastSync) < Double(channelSyncSkipWindow.components.seconds) {
          return true
        }
        if let lastAttempt = lastAttemptedChannelSync,
           now.timeIntervalSince(lastAttempt) < Double(channelSyncSkipWindow.components.seconds) {
          return true
        }
        return false
      }()

      if shouldSkipChannels {
        channelStatus = .skipped
        logger.info("[Sync] Skipping channel sync (recent clean or attempted sync)")
      } else {
        logger.info("[Sync] State → .syncing(.channels)")
        await setState(.syncing(progress: SyncProgress(phase: .channels, current: 0, total: 0)))
        let maxChannels = device?.maxChannels ?? 0
        await onChannelSyncAttempted?(radioID)

        let channelStart = ContinuousClock.now
        let channelResult: ChannelSyncResult
        do {
          channelResult = try await channelService.syncChannels(
            radioID: radioID,
            maxChannels: maxChannels,
            usePipelinedRead: usePipelinedRead
          )
        } catch let error as CancellationError {
          throw error
        } catch {
          let channelElapsed = ContinuousClock.now - channelStart
          channelStatus = .partial
          logger.warning(
            "[Sync] Phase end: channels partial after thrown error in \(channelElapsed): \(error.localizedDescription)"
          )
          await logPostSyncChannelDiagnostics(radioID: radioID, dataStore: dataStore)
          if let rxLogService {
            await refreshRxLogChannels(radioID: radioID, dataStore: dataStore, rxLogService: rxLogService)
          }
          return ContactChannelSyncResult(
            contacts: .clean,
            channels: channelStatus,
            channelRetryIndices: channelRetryIndices
          )
        }
        let channelElapsed = ContinuousClock.now - channelStart
        logger.info("[Sync] Phase end: channels - \(channelResult.channelsSynced) synced (device capacity: \(maxChannels)) in \(channelElapsed)")

        var channelPhaseClean = channelResult.isComplete
        let hasNonRetryableErrors = channelResult.errors.count > channelResult.retryableIndices.count
        var remainingRetryableIndices = channelResult.retryableIndices

        // Retry failed channels once if there are retryable errors
        if !channelResult.isComplete {
          let retryableIndices = channelResult.retryableIndices
          if !retryableIndices.isEmpty {
            logger.info("Retrying \(retryableIndices.count) failed channels")
            let retryResult: ChannelSyncResult
            do {
              retryResult = try await channelService.retryFailedChannels(
                radioID: radioID,
                indices: retryableIndices
              )
            } catch let error as CancellationError {
              throw error
            } catch {
              logger.warning("Channel retry failed: \(error.localizedDescription)")
              channelPhaseClean = false
              remainingRetryableIndices = retryableIndices
              retryResult = ChannelSyncResult(
                channelsSynced: 0,
                errors: retryableIndices.map {
                  ChannelSyncError(
                    index: $0,
                    errorType: .timeout,
                    description: error.localizedDescription
                  )
                }
              )
            }

            if retryResult.isComplete, !hasNonRetryableErrors {
              logger.info("Retry recovered \(retryResult.channelsSynced) channels")
              channelPhaseClean = true
            } else {
              remainingRetryableIndices = retryResult.retryableIndices
              if hasNonRetryableErrors {
                logger.warning("Channels have non-retryable errors, phase not clean")
              }
              logger.warning("Channels still failing after retry: \(retryResult.errors.map(\.index))")
              channelPhaseClean = false
            }
          }
        }

        if channelPhaseClean {
          channelStatus = .clean
          await onCleanChannelSync?(radioID)
        } else {
          channelStatus = .partial
          channelRetryIndices = remainingRetryableIndices
        }
      }

      await logPostSyncChannelDiagnostics(radioID: radioID, dataStore: dataStore)
      if let rxLogService {
        await refreshRxLogChannels(radioID: radioID, dataStore: dataStore, rxLogService: rxLogService)
      }
    } else {
      channelStatus = .skipped
      logger.info("Skipping channel sync (app in background)")
    }

    return ContactChannelSyncResult(
      contacts: .clean,
      channels: channelStatus,
      channelRetryIndices: channelRetryIndices
    )
  }

  /// Logs once when invalid-watermark recovery is spent and rounds fall back to
  /// the stored stamp (pathological residual far-future lastmod table).
  private func logInvalidWatermarkRecoveryExhaustedIfNeeded(radioID: UUID, stored: UInt32) {
    guard invalidWatermarkRecoveryExhaustedLoggedRadioID != radioID else { return }
    invalidWatermarkRecoveryExhaustedLoggedRadioID = radioID
    logger.notice(
      "[Sync] Invalid watermark recovery exhausted for radio \(radioID.uuidString): stored \(stored) still implausible — using stored stamp; manual contact refresh required"
    )
  }

  /// Runs contact sync and writes back the watermark when the result carries one.
  /// Shared by full connect sync and advert-driven delta sync.
  @discardableResult
  private func syncContactsPhase(
    radioID: UUID,
    dataStore: any PersistenceStoreProtocol,
    contactService: some ContactServiceProtocol,
    since: Date?,
    forceFullSync: Bool = false
  ) async throws -> ContactSyncResult {
    // Caller already logged prune-full cases; epoch-0 prune-free fetch is silent here.
    if let watermark = since {
      logger.info("[Sync] Phase start: contacts (incremental, watermark=\(watermark.formatted(.iso8601)))")
    }

    let contactStart = ContinuousClock.now
    let contactResult = try await contactService.syncContacts(radioID: radioID, since: since)
    let contactElapsed = ContinuousClock.now - contactStart
    let syncType = contactResult.isIncremental ? "incremental" : "full"
    let forced = forceFullSync ? ", forced" : ""
    logger.info("[Sync] Phase end: contacts - \(contactResult.contactsReceived) (\(syncType)\(forced)) in \(contactElapsed)")
    await notifyContactsChanged()

    if contactResult.lastSyncTimestamp > 0 {
      try await dataStore.updateDeviceLastContactSync(
        radioID: radioID,
        timestamp: contactResult.lastSyncTimestamp
      )
    }
    if since == nil {
      fullContactSyncCompletedRadioID = radioID
    }
    return contactResult
  }

  /// Suspends while an advert-driven delta sync holds the claim.
  ///
  /// Background advert work must not turn a full sync, connection setup, or
  /// channel-only retry into a silent skip, and must not interleave its
  /// `ContactServiceEvent.syncProgress` events with a user-initiated refresh.
  ///
  /// Throws `CancellationError` when the calling task is cancelled, and
  /// `SyncCoordinatorError.syncFailed` when `timeout` elapses while the claim
  /// is still held. On timeout the caller must not proceed into the radio
  /// pipeline (that races progress events) and must not return a silent skip
  /// for user-initiated work (the wait exists to stop that). Surface an error
  /// so the spinner stops and the user can retry.
  func waitForAdvertContactSync(
    timeout: Duration? = nil
  ) async throws {
    let bound = timeout
      ?? advertContactSyncWaitTimeoutOverride
      ?? Self.advertContactSyncWaitTimeout
    let deadline = ContinuousClock.now + bound

    while isSyncInProgress, advertContactSyncActive {
      try Task.checkCancellation()

      let remaining = deadline - ContinuousClock.now
      if remaining <= .zero {
        logger.warning("Timed out waiting for advert contact sync to release claim")
        throw SyncCoordinatorError.syncFailed(Self.advertContactSyncWaitTimedOutMessage)
      }

      let waiterID = nextAdvertSyncWaiterID
      nextAdvertSyncWaiterID &+= 1
      try await waitForAdvertClaimRelease(id: waiterID, timeout: remaining)
    }
  }

  /// Waits out an advert claim, then marks a user-initiated contact refresh
  /// active in the same actor turn so no delta can interleave between observe
  /// and claim (separate wait and claim hops leave that window).
  func claimManualContactSync(
    timeout: Duration? = nil
  ) async throws {
    try await waitForAdvertContactSync(timeout: timeout)
    manualContactSyncActive = true
  }

  /// Marks a user-initiated contact refresh so advert delta sync returns `.busy`.
  func setManualContactSyncActive(_ active: Bool) {
    manualContactSyncActive = active
  }

  /// Test hook: shortens the advert claim wait bound used by default parameters.
  func setAdvertContactSyncWaitTimeoutOverride(_ timeout: Duration?) {
    advertContactSyncWaitTimeoutOverride = timeout
  }

  /// Suspends until the advert claim clears, the wait is cancelled, or `timeout` elapses.
  private func waitForAdvertClaimRelease(id: UInt64, timeout: Duration) async throws {
    try await withThrowingTaskGroup(of: Void.self) { group in
      group.addTask {
        try await self.suspendUntilAdvertClaimReleased(id: id)
      }
      group.addTask {
        try await Task.sleep(for: timeout)
        throw SyncCoordinatorError.syncFailed(Self.advertContactSyncWaitTimedOutMessage)
      }
      // First finisher wins: claim release, cancel, or timeout.
      try await group.next()
      group.cancelAll()
    }
  }

  /// Parks one waiter until resume, cancel, or timeout-driven cancellation.
  private func suspendUntilAdvertClaimReleased(id: UInt64) async throws {
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        if Task.isCancelled {
          continuation.resume(throwing: CancellationError())
          return
        }
        // Claim may have cleared between the while check and registration.
        if !(isSyncInProgress && advertContactSyncActive) {
          continuation.resume()
          return
        }
        advertSyncWaiters.append(AdvertSyncWaiter(id: id, continuation: continuation))
      }
    } onCancel: {
      Task { await self.finishAdvertSyncWaiter(id: id, error: CancellationError()) }
    }
  }

  /// Resumes one waiter if it is still registered (cancel/timeout path).
  private func finishAdvertSyncWaiter(id: UInt64, error: Error) {
    guard let index = advertSyncWaiters.firstIndex(where: { $0.id == id }) else { return }
    let waiter = advertSyncWaiters.remove(at: index)
    waiter.continuation.resume(throwing: error)
  }

  /// Resumes every advert-claim waiter. Used when the claim clears or on disconnect.
  private func resumeAllAdvertSyncWaiters() {
    let waiters = advertSyncWaiters
    advertSyncWaiters = []
    for waiter in waiters {
      waiter.continuation.resume()
    }
  }

  /// Advert-driven contact delta sync. Claims `isSyncInProgress` as advert-owned
  /// so full sync waits rather than skips.
  ///
  /// Both modes need proof that a full contact fetch already succeeded for this
  /// radio. Without it, writing a watermark here would make later full syncs
  /// incremental and leave ghost contacts the device no longer has. An empty
  /// radio stamps no watermark, so a completed full fetch grants entry on its
  /// own and the round fetches from epoch zero until the first contact stamps one.
  ///
  /// - Parameter fullRefetch: When true, uses the epoch-0 prune-free full fetch.
  ///   When false, uses the stored watermark.
  func performAdvertContactSync(
    fullRefetch: Bool,
    radioID: UUID,
    dataStore: any PersistenceStoreProtocol,
    contactService: some ContactServiceProtocol
  ) async -> AdvertContactSyncOutcome {
    guard !manualContactSyncActive else {
      logger.info("Advert contact sync deferred because a manual contact refresh is active")
      return .busy
    }
    guard !isSyncInProgress else {
      logger.info("Advert contact sync deferred because sync is already active")
      return .busy
    }
    isSyncInProgress = true
    advertContactSyncActive = true
    defer {
      isSyncInProgress = false
      advertContactSyncActive = false
      resumeAllAdvertSyncWaiters()
    }

    do {
      let device = try await dataStore.fetchDevice(radioID: radioID)
      let watermarkUse = Self.contactWatermarkUse(fromLastContactSync: device?.lastContactSync)
      let hasCompletedFullSync = fullContactSyncCompletedRadioID == radioID
      // `.invalid` still proves a prior stamp exists, so recovery may run.
      let isReady = watermarkUse != .none || hasCompletedFullSync
      guard isReady else {
        logger.notice(
          "[Sync] Advert contact sync skipped: no contact watermark yet (connect sync contacts phase has not succeeded)"
        )
        return .notReady
      }

      var ranInvalidWatermarkRecovery = false
      let since: Date?
      if fullRefetch {
        logger.info("[Sync] Advert contact sync: prune-free full refetch")
        since = Self.pruneFreeFullFetchSince
      } else {
        switch watermarkUse {
        case .none:
          logger.info("[Sync] Advert contact sync: prune-free full fetch (full sync found no contacts to stamp)")
          since = Self.pruneFreeFullFetchSince
        case let .incremental(watermark):
          since = Self.incrementalSince(watermark: watermark)
        case let .invalid(stored):
          // Prune-free epoch-0 only. `since == nil` would delete local rows the
          // device omits; connect full-sync may prune, background advert must not.
          // One recovery full fetch per radio per coordinator lifetime; further
          // rounds use the stored stamp so residual far-future lastmods cannot
          // re-stream the whole table every debounce.
          if invalidWatermarkRecoveryRadioID == radioID {
            since = Self.incrementalSince(watermark: stored)
            logInvalidWatermarkRecoveryExhaustedIfNeeded(radioID: radioID, stored: stored)
          } else {
            ranInvalidWatermarkRecovery = true
            logger.notice(
              "[Sync] Advert contact sync: invalid watermark \(stored) exceeds phone reference + \(Int(Self.contactWatermarkPlausibilitySkew))s — one-shot prune-free full recovery, store not rewritten"
            )
            since = Self.pruneFreeFullFetchSince
          }
        }
      }

      _ = try await syncContactsPhase(
        radioID: radioID,
        dataStore: dataStore,
        contactService: contactService,
        since: since
      )
      if ranInvalidWatermarkRecovery {
        invalidWatermarkRecoveryRadioID = radioID
      }
      return .synced
    } catch {
      logger.warning("Advert contact sync failed: \(error.localizedDescription)")
      return .failed
    }
  }

  /// Retries only unresolved channel indices without replaying contacts/messages.
  @discardableResult
  func retryChannels(
    radioID: UUID,
    channelService: some ChannelServiceProtocol,
    indices: [UInt8]
  ) async -> ChannelSyncResult {
    guard !indices.isEmpty else {
      return ChannelSyncResult(channelsSynced: 0, errors: [])
    }

    do {
      try await waitForAdvertContactSync()
    } catch is CancellationError {
      return ChannelSyncResult(
        channelsSynced: 0,
        errors: indices.map {
          ChannelSyncError(index: $0, errorType: .transportError, description: "Retry cancelled")
        }
      )
    } catch {
      logger.warning("Channel-only retry timed out waiting for advert contact sync")
      return ChannelSyncResult(
        channelsSynced: 0,
        errors: indices.map {
          ChannelSyncError(
            index: $0,
            errorType: .circuitBreaker,
            description: Self.advertContactSyncWaitTimedOutMessage
          )
        }
      )
    }

    guard !isSyncInProgress else {
      logger.info("Channel-only retry skipped because sync is already active")
      return ChannelSyncResult(
        channelsSynced: 0,
        errors: indices.map {
          ChannelSyncError(
            index: $0,
            errorType: .circuitBreaker,
            description: "Skipped because sync is already active"
          )
        }
      )
    }

    isSyncInProgress = true
    defer { isSyncInProgress = false }

    logger.info("[Sync] State → .syncing(.channels) (channel-only retry)")
    await setState(.syncing(progress: SyncProgress(phase: .channels, current: 0, total: indices.count)))
    await onSyncActivityStarted?()
    await onChannelSyncAttempted?(radioID)

    let result: ChannelSyncResult
    do {
      result = try await channelService.retryFailedChannels(radioID: radioID, indices: indices)
    } catch is CancellationError {
      await onSyncActivityEnded?(false)
      await setState(.idle)
      return ChannelSyncResult(
        channelsSynced: 0,
        errors: indices.map {
          ChannelSyncError(index: $0, errorType: .transportError, description: "Retry cancelled")
        }
      )
    } catch {
      logger.warning("Channel-only retry failed: \(error.localizedDescription)")
      await onSyncActivityEnded?(false)
      await setState(.synced)
      return ChannelSyncResult(
        channelsSynced: 0,
        errors: indices.map {
          ChannelSyncError(index: $0, errorType: .transportError, description: error.localizedDescription)
        }
      )
    }

    if result.isComplete {
      await onCleanChannelSync?(radioID)
    }

    await onSyncActivityEnded?(result.isComplete)
    await setState(.synced)
    return result
  }

  /// Cancels the suppression watchdog, resumes notifications, then waits for pending
  /// message handlers to drain. Suppression is cleared first as defense-in-depth for
  /// error paths where `performFullSync` throws before reaching `pollAllMessages()`.
  /// Both operations are idempotent, so double-clearing from the happy path is harmless.
  private func drainHandlersAndResumeNotifications(
    notificationService: NotificationService,
    messagePollingService: any MessagePollingServiceProtocol,
    context: String
  ) async {
    cancelSuppressionWatchdog()
    await MainActor.run {
      logger.info("Resuming message notifications (\(context))")
      notificationService.isSuppressingNotifications = false
    }

    let pendingHandlerDrainTimeout: Duration = .seconds(30)
    let didDrainPendingHandlers = await messagePollingService.waitForPendingHandlers(timeout: pendingHandlerDrainTimeout)
    if !didDrainPendingHandlers {
      logger.warning("Timed out waiting for pending message handlers")
    }
  }

  private func logPostSyncChannelDiagnostics(radioID: UUID, dataStore: any PersistenceStoreProtocol) async {
    do {
      let channels = try await dataStore.fetchChannels(radioID: radioID)
      let emptyNameWithSecretIndices = channels
        .filter { $0.name.isEmpty && $0.hasSecret }
        .map(\.index)
        .sorted()
      logger.info(
        "Post-sync channel diagnostics: total=\(channels.count), emptyNameWithSecret=\(emptyNameWithSecretIndices.count)"
      )
      if !emptyNameWithSecretIndices.isEmpty {
        logger.warning(
          "Post-sync channels with empty names and non-zero secrets: \(emptyNameWithSecretIndices)"
        )
      }
    } catch {
      logger.error("Failed to compute post-sync channel diagnostics: \(error)")
    }
  }

  private func refreshRxLogChannels(
    radioID: UUID,
    dataStore: any PersistenceStoreProtocol,
    rxLogService: RxLogService
  ) async {
    do {
      let channels = try await dataStore.fetchChannels(radioID: radioID)
      let secrets = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.secret) })
      let names = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.name) })
      await rxLogService.updateChannels(secrets: secrets, names: names)
      logger.debug("Refreshed RxLogService channel cache with \(channels.count) channels")
    } catch {
      logger.error("Failed to refresh RxLogService channel cache: \(error)")
    }
  }
}
