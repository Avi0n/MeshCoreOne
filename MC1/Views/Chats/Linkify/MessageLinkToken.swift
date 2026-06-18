import SwiftUI

/// One detected, styled span in a normalized message body. The tokenizer emits a
/// sorted, non-overlapping `[LinkToken]`; the styler walks them to author the single
/// SwiftUI `AttributedString`. Every styling decision a detector makes (which color,
/// whether to underline, whether to embolden) is frozen on the token here, so the
/// styler stays a mechanical apply step with no per-kind branching.
struct LinkToken {
    /// Detector kinds in their fixed overlap-resolution priority, highest first (the case
    /// order is the priority). When two detections cover overlapping characters the
    /// higher-priority case wins and the lower is dropped, so the merged stream is
    /// non-overlapping. The order encodes how each overlapping pair resolves: a contact chip
    /// or mention shadows any link, hashtag, or coordinate inside its run; a URL shadows a
    /// hashtag or coordinate it contains; and a hashtag sitting inside a meshcore link wins
    /// over that link, so the meshcore link is left unlinked and only the hashtag is styled.
    enum Kind: Int, Comparable {
        case contactShare
        case mention
        case url
        case hashtag
        case meshcoreLink
        case coordinate

        static func < (lhs: Kind, rhs: Kind) -> Bool {
            lhs.rawValue < rhs.rawValue
        }
    }

    let range: Range<String.Index>
    let kind: Kind

    /// The link to install, or nil when a span is styled but not tappable (a mention
    /// whose name cannot be percent-encoded keeps its color and underline but no link).
    let url: URL?

    /// Foreground color for the span. Resolved at detection time against the live
    /// theme/contrast inputs, so the styler does not re-derive any color.
    let foregroundColor: Color

    /// Self-mention highlight, applied behind the span when present.
    let backgroundColor: Color?

    let underline: Bool

    /// Hashtags render bold via `inlinePresentationIntent = .stronglyEmphasized`;
    /// no other kind sets it.
    let bold: Bool
}
