import Foundation

/// Generation-stamped write capability for a `ChatCoordinator` timeline.
///
/// Minted exclusively by `ChatCoordinator.bindWriter(owner:role:...)`; the
/// coordinator's mutation methods are internal to MC1Services, so holding a
/// writer is the only way app code can mutate a timeline. Every forwarder
/// checks the mint generation against the coordinator's current one: once a
/// newer writer is bound, this writer's mutations no-op, so a stale prime
/// (or a superseded view model) can never write over the live conversation.
///
/// Reads are not gated; consumers keep reading `coordinator.renderState`,
/// `messages`, and `messagesByID` directly.
@MainActor
public final class ChatTimelineWriter {
  private let coordinator: ChatCoordinator
  private let generation: UInt64

  /// Role this writer was bound with. Diagnostic only; staleness is
  /// decided purely by generation.
  public let role: ChatWriterRole

  init(coordinator: ChatCoordinator, generation: UInt64, role: ChatWriterRole) {
    self.coordinator = coordinator
    self.generation = generation
    self.role = role
  }

  /// Whether this writer still holds write access.
  public var isCurrent: Bool {
    generation == coordinator.writerGeneration
  }

  /// Runs `body` only while this writer is current. A dropped `.prime`
  /// write is expected teardown noise; a dropped `.interactive` write
  /// almost always means a missed rebind (BFU scene rebuild, role
  /// misassignment) and would present as quietly frozen bubbles, so it
  /// logs at fault level in DEBUG builds.
  private func ifCurrent(_ operation: String, _ body: () -> Void) {
    guard isCurrent else {
      #if DEBUG
        if role == .interactive {
          coordinator.logger.fault("stale interactive writer dropped \(operation, privacy: .public); missed rebind?")
        } else {
          coordinator.logger.info("stale \(self.role.rawValue, privacy: .public) writer dropped \(operation, privacy: .public)")
        }
      #else
        coordinator.logger.info("stale \(self.role.rawValue, privacy: .public) writer dropped \(operation, privacy: .public)")
      #endif
      return
    }
    body()
  }

  // MARK: - Message-list mutations

  public func replaceAll(_ newMessages: [MessageDTO]) {
    ifCurrent("replaceAll") { coordinator.replaceAll(newMessages) }
  }

  public func beginLoading() {
    ifCurrent("beginLoading") { coordinator.beginLoading() }
  }

  public func markLoaded() {
    ifCurrent("markLoaded") { coordinator.markLoaded() }
  }

  public func prepend(_ older: [MessageDTO]) {
    ifCurrent("prepend") { coordinator.prepend(older) }
  }

  /// Returns `false` when the message was already present, or when this
  /// writer is stale and nothing was appended.
  @discardableResult
  public func append(_ message: MessageDTO) -> Bool {
    var appended = false
    ifCurrent("append") { appended = coordinator.append(message) }
    return appended
  }

  public func update(messageID: UUID, _ transform: (inout MessageDTO) -> Void) {
    ifCurrent("update") { coordinator.update(messageID: messageID, transform) }
  }

  public func remove(messageID: UUID) {
    ifCurrent("remove") { coordinator.remove(messageID: messageID) }
  }

  public func replaceMessagesPreservingByID(_ reordered: [MessageDTO]) {
    ifCurrent("replaceMessagesPreservingByID") { coordinator.replaceMessagesPreservingByID(reordered) }
  }

  // MARK: - Render-state mutations

  public func updateRenderState(_ transform: (ChatRenderState) -> ChatRenderState) {
    ifCurrent("updateRenderState") { coordinator.updateRenderState(transform) }
  }

  public func appendRenderItem(_ item: MessageItem) {
    ifCurrent("appendRenderItem") { coordinator.appendRenderItem(item) }
  }

  public func updateRenderItem(id: UUID, _ transform: (MessageItem) -> MessageItem) {
    ifCurrent("updateRenderItem") { coordinator.updateRenderItem(id: id, transform) }
  }

  public func removeRenderItem(id: UUID) {
    ifCurrent("removeRenderItem") { coordinator.removeRenderItem(id: id) }
  }

  public func applyStatusUpdate(
    messageID: UUID,
    status: MessageStatus,
    roundTripTime: UInt32? = nil,
    userInitiated: Bool = false
  ) {
    ifCurrent("applyStatusUpdate") {
      coordinator.applyStatusUpdate(
        messageID: messageID,
        status: status,
        roundTripTime: roundTripTime,
        userInitiated: userInitiated
      )
    }
  }

  // MARK: - Rebuild / reload scheduling

  /// Scheduling a full bake is a write: `rebuildItems` bumps the
  /// generation counter before capturing it, so the last-scheduled build
  /// always wins. That is exactly why a stale writer must not be able to
  /// schedule one.
  public func rebuildItems(
    inputs: [(MessageDTO, MessageBuildInputs)],
    envInputs: EnvInputs,
    postApply: (@MainActor () -> Void)? = nil
  ) {
    ifCurrent("rebuildItems") {
      coordinator.rebuildItems(inputs: inputs, envInputs: envInputs, postApply: postApply)
    }
  }

  public func enqueueReload(updatedMessageIDs: Set<UUID>) {
    ifCurrent("enqueueReload") { coordinator.enqueueReload(updatedMessageIDs: updatedMessageIDs) }
  }

  public func enqueueReload(messageID: UUID) {
    ifCurrent("enqueueReload") { coordinator.enqueueReload(messageID: messageID) }
  }
}
