import Foundation
import SwiftUI
import MC1Services

/// One row in the chat timeline as the bubble sees it. Built from a `MessageDTO`
/// plus current render state plus per-message build inputs. `Sendable` so it can
/// flow across the UIKit-table cell-config boundary without copy-on-write
/// concerns.
struct MessageItem: Identifiable, Sendable, Hashable {
    let id: UUID
    let envelope: MessageEnvelope
    let content: [MessageFragment]
    let footer: MessageFooter
    let grouping: GroupingFlags
    let shouldRequestPreviewFetch: Bool
}

/// Per-message identity, direction, status, sender — everything not specific
/// to one content fragment.
///
/// `hasFailed` mirrors the DTO field `hasFailed`, not `status == .failed` — the
/// DTO field stays true while a message is in `.retrying`, and that drives the
/// failed-bubble red coloring used by the existing bubble. Pin to the DTO field;
/// the failed-bubble red is a primary outdoor-visibility cue.
struct MessageEnvelope: Sendable, Hashable {
    let messageID: UUID
    let isOutgoing: Bool
    let senderName: String
    let senderResolution: NodeNameResolution
    let status: MessageStatus
    let date: Date
    let hasFailed: Bool
    let containsSelfMention: Bool
    let mentionSeen: Bool
}

/// Hop / path / region — derived from `MessageBubblePredicates`-equivalent
/// inputs plus `formattedPath`. Stays a struct (not a fragment) because hop /
/// path / region render as one footer row, not three independent rows.
///
/// `status` is stored as `MessageStatus` (the raw enum) rather than a pre-built
/// localized status string. Resolving `L10n` at build time would freeze the
/// localized text into the fragment, so a language switch mid-session would
/// leave bubbles displaying stale strings until a rebuild. The view body
/// resolves the localized text at render time.
struct MessageFooter: Sendable, Hashable {
    let showHop: Bool
    let hopCount: Int
    let formattedPath: String?
    let regionToShow: String?
    let showStatusRow: Bool
    let status: MessageStatus
    let heardRepeats: Int
    let retryAttempt: Int
    let maxRetryAttempts: Int
}

/// Grouping signal — first-in-cluster, show timestamp, show divider.
struct GroupingFlags: Sendable, Hashable {
    let showTimestamp: Bool
    let showDirectionGap: Bool
    let showSenderName: Bool
    let showNewMessagesDivider: Bool
}

/// Closed enum: each variant describes one row of content inside a bubble.
/// Adding a case forces every consumer to update — that is the type-system win
/// this refactor buys.
///
/// The payload type for `.text` is `MessageTextPayload` rather than
/// `MessageText` because module `MC1` already exports a SwiftUI view named
/// `MessageText`.
enum MessageFragment: Sendable, Hashable {
    case text(MessageTextPayload)
    case inlineImage(InlineImage)
    case linkPreview(LinkPreviewFragmentState)
    case malwareWarning(URL)
    /// Carries the raw summary string (`"👍:3,❤️:2,😂:1"` format produced by
    /// `ReactionParser`). The DTO field `reactionSummary` is `String?`; the
    /// fragment is only emitted when the value is non-nil and non-empty, so
    /// the payload is a non-optional `String`. Parsing back into per-emoji
    /// counts happens at render time.
    case reactionSummary(String)
}

struct MessageTextPayload: Sendable, Hashable {
    let raw: String
    let formatted: AttributedString?
    let baseColor: Color
    let isOutgoing: Bool
    let currentUserName: String
}

struct InlineImage: Sendable, Hashable {
    enum LoadState: Sendable, Hashable {
        case idle(URL)
        case loading(URL)
        case loaded(ImageReference, isGIF: Bool)
        case failed(URL)
    }
    let state: LoadState
    let autoPlayGIFs: Bool
}

struct LinkPreviewFragmentState: Sendable, Hashable {
    enum Mode: Sendable, Hashable {
        case idle
        case loading(URL)
        case loaded(LinkPreviewDataDTO, image: ImageReference?, icon: ImageReference?)
        case noPreview
        case disabled(URL)
        case legacy(url: URL, title: String?, image: ImageReference?, icon: ImageReference?)
    }
    let mode: Mode
}

/// Type-erased handle to a UIImage that lives on the view model. Hashable via
/// cache key + role discriminator; equality is structural. `Sendable` because
/// it only carries a Hashable key. The bubble resolves the actual UIImage via
/// an `imageResolver: (ImageReference) -> UIImage?` callback at render time.
struct ImageReference: Sendable, Hashable {
    let cacheKey: UUID
    let role: Role
    enum Role: Sendable, Hashable {
        case inline, linkPreviewImage, linkPreviewIcon
    }
}
