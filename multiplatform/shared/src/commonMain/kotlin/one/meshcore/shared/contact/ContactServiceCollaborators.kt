package one.meshcore.shared.contact

/** Side effects for sync, cleanup, discovery, orphan adoption, and the discover table. */
data class ContactServiceCollaborators(
    val syncCoordinator: ContactSyncCoordinator = NoOpContactSyncCoordinator,
    val cleanupCoordinator: ContactCleanupHandling = NoOpContactCleanupHandling,
    val discoveryNotifier: ContactDiscoveryNotifier = ContactDiscoveryNotifier.NoOp,
    val orphanDmAdopter: OrphanDmAdopter = OrphanDmAdopter.NoOp,
    /**
     * Discover table dual-write for on-air lastHeard.
     * Null in unit tests that only exercise the contact row.
     */
    val discoveredNodes: DiscoveredNodeStore? = null,
)
