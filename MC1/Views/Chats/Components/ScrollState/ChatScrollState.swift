import Foundation

/// Whether the user is actively driving the scroll surface.
enum InteractionState: Equatable, Sendable {
    case idle
    case dragging
}

/// What the controller wants the scroll surface to do, if anything.
enum ScrollIntent: Equatable, Sendable {
    case none
    case toBottom
    case toTarget(id: UUID)
}

/// Whether a snapshot apply is in flight.
enum ApplyState: Equatable, Sendable {
    case idle
    case applying
}

/// Captures a scroll-to-bottom request that was deferred because the user was
/// dragging. Coexists with InteractionState.dragging. Resolved when interaction
/// ends.
struct DeferredScroll: Equatable, Sendable {
    let targetMessageCount: Int
    let createdAt: Date
}

/// Container that bundles the three axes plus DeferredScroll for atomic
/// observation. Pure value-type; mutations go through helper methods.
struct ChatScrollState: Equatable, Sendable {
    var interaction: InteractionState
    var intent: ScrollIntent
    var apply: ApplyState
    var deferredScroll: DeferredScroll?

    static let idle = ChatScrollState(
        interaction: .idle, intent: .none, apply: .idle, deferredScroll: nil
    )

    /// True when the controller should not start a new scroll intent because
    /// the user is dragging. The dragging axis is independent of intent —
    /// existing intents continue.
    var isUserDriven: Bool { interaction == .dragging }

    /// True when any programmatic scroll is in flight. Coexists with
    /// interaction.dragging (e.g., reloadTargetCell applies a snapshot during
    /// scroll-to-target).
    var isApplyingSnapshot: Bool { apply == .applying }
}

extension ChatScrollState {
    mutating func enterDragging() { interaction = .dragging }

    mutating func endDragging() {
        interaction = .idle
    }

    mutating func startApplying() { apply = .applying }
    mutating func endApplying() { apply = .idle }

    mutating func startIntent(_ next: ScrollIntent) {
        intent = next
    }
    mutating func clearIntent() { intent = .none }

    /// Stores a scroll request to replay once the user stops dragging.
    mutating func scheduleDeferredScroll(_ scroll: DeferredScroll) {
        deferredScroll = scroll
    }

    mutating func consumeDeferredScroll() -> DeferredScroll? {
        defer { deferredScroll = nil }
        return deferredScroll
    }
}
