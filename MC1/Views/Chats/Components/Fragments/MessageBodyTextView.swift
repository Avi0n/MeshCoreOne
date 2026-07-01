import OSLog
import SwiftUI
import UIKit

private let logger = Logger(subsystem: "com.mc1", category: "MessageBodyTextView")

/// The single text/link rendering path for a message body. A `UITextView`-backed
/// `UIViewRepresentable` that replaces `Text(AttributedString)` and renders passively
/// (non-selectable): it installs no link, selection, or edit interactions, so the bubble's
/// `.onLongPressGesture` always wins (a long-press anywhere, including over a URL or coordinate,
/// opens the actions sheet) and a Mac secondary click falls through to the table's context-menu
/// interaction. Link taps are detected by a tap recognizer that hit-tests the `.link` attribute and
/// routes through the injected `OpenURLAction`. No message content is special-cased.
///
/// The model stays `Sendable`/`Hashable`: this view takes the existing precomputed SwiftUI
/// `AttributedString` and derives the UIKit `NSAttributedString` at render time, on the main
/// actor with the live trait collection, rather than storing a non-`Sendable` `NSAttributedString`
/// on the payload.
struct MessageBodyTextView: UIViewRepresentable {
  /// The single authored representation. The UIKit string is derived from this, never authored
  /// in parallel, so every link kind's color/underline/bold/link has one source of truth.
  let attributedString: AttributedString

  /// The base text color slot resolved by the bubble. Incoming bodies use `.primary` (mapped to
  /// `UIColor.label` at bridge time); outgoing bodies use the filled-bubble text color, which
  /// already bridges to a stable `UIColor`.
  let baseColor: Color

  /// Dynamic Type token threaded from the SwiftUI environment so a category change invalidates
  /// the Coordinator size cache. `updateUIView` runs on any environment change, so a reflow of
  /// already-visible bubbles is driven from here in concert with the table's reconfigure.
  let contentSizeCategoryToken: String

  @Environment(\.openURL) private var openURL

  func makeCoordinator() -> Coordinator {
    Coordinator()
  }

  func makeUIView(context: Context) -> BubbleBodyTextView {
    let textView = BubbleBodyTextView(usingTextLayoutManager: false)
    textView.configureForBubble()

    textView.isEditable = false
    textView.isScrollEnabled = false
    textView.adjustsFontForContentSizeCategory = true

    // Non-selectable: a passive renderer installs no link, selection, or edit interactions, so
    // a long-press falls through to the bubble's gesture and a secondary click falls through to
    // the table's context-menu interaction. Link taps are detected by the recognizer below.
    textView.isSelectable = false
    textView.isUserInteractionEnabled = true

    // A long-press must not become a drag or a text loupe; both would steal the gesture the
    // bubble needs. Dropping the drop interaction prevents a long-press-into-drop hijack too.
    textView.textDragInteraction?.isEnabled = false
    if let dropInteraction = textView.textDropInteraction {
      textView.removeInteraction(dropInteraction)
    }

    textView.backgroundColor = .clear
    textView.textContainerInset = .zero
    textView.textContainer.lineFragmentPadding = 0
    textView.contentInsetAdjustmentBehavior = .never

    // Hug content in both axes so the hosting cell self-sizes from the measured text.
    textView.setContentHuggingPriority(.required, for: .vertical)
    textView.setContentCompressionResistancePriority(.required, for: .vertical)
    textView.setContentHuggingPriority(.defaultLow, for: .horizontal)

    // Single tap routes a link through the injected OpenURLAction; a tap that hits no link is
    // not consumed (cancelsTouchesInView = false), so the bubble's tap and long-press still fire.
    let tapRecognizer = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.handleTap(_:))
    )
    tapRecognizer.cancelsTouchesInView = false
    // Off Mac, the text view is the recognizer's delegate so the bubble's long-press wins a
    // contested press (see `BubbleBodyTextView`). On Mac the secondary click routes through the
    // table's context-menu interaction, which this delegate must not disturb.
    if !ProcessInfo.processInfo.isiOSAppOnMac {
      tapRecognizer.delegate = textView
    }
    textView.addGestureRecognizer(tapRecognizer)

    apply(to: textView, context: context)
    return textView
  }

  func updateUIView(_ textView: BubbleBodyTextView, context: Context) {
    apply(to: textView, context: context)
  }

  /// Bridges the SwiftUI string to UIKit against the live trait collection, refreshes the live
  /// `OpenURLAction` on the Coordinator (so a recycled cell picks up the current action), and
  /// invalidates the size cache when the bridged content or Dynamic Type token changes.
  private func apply(to textView: BubbleBodyTextView, context: Context) {
    let coordinator = context.coordinator
    coordinator.openURL = openURL

    let bridged = Self.makeAttributedText(
      from: attributedString,
      baseColor: baseColor,
      traitCollection: textView.traitCollection
    )

    let contentHash = Self.contentHash(
      attributedString: attributedString,
      contentSizeCategoryToken: contentSizeCategoryToken
    )
    if coordinator.contentHash != contentHash {
      coordinator.contentHash = contentHash
      coordinator.invalidateSizeCache()
    }

    textView.attributedText = bridged
  }

  func sizeThatFits(
    _ proposal: ProposedViewSize,
    uiView: BubbleBodyTextView,
    context: Context
  ) -> CGSize? {
    let width = proposal.width ?? UIView.layoutFittingCompressedSize.width
    guard width.isFinite, width > 0 else { return nil }

    let coordinator = context.coordinator
    let key = SizeCacheKey(width: Double(width), contentHash: coordinator.contentHash)
    if let cached = coordinator.computedSizes[key] {
      return cached
    }

    let fitting = uiView.sizeThatFits(
      CGSize(width: width, height: UIView.layoutFittingCompressedSize.height)
    )
    let resolved = CGSize(width: min(fitting.width, width), height: fitting.height)

    // A size-cache miss is the highest-attention surface: a stale height here would surface as a
    // diffable reflow glitch, so each miss is logged for diagnosis.
    logger.debug(
      "size cache miss width=\(width, format: .fixed(precision: 1)) hash=\(coordinator.contentHash) measured=\(resolved.height, format: .fixed(precision: 1))"
    )

    // Writing the cache synchronously inside sizeThatFits would mutate Coordinator state during
    // layout, which is the surface this project's diffable scroll crashes are tied to. Defer it,
    // and store the entry only if the content discriminator still matches: if the bridged
    // content or Dynamic Type token changed between this miss and the deferred block, the key is
    // dead and writing it back would leak a stale entry.
    DispatchQueue.main.async {
      guard coordinator.contentHash == key.contentHash else { return }
      coordinator.computedSizes[key] = resolved
    }
    return resolved
  }

  // MARK: - Render-time bridging

  /// Base text style for the whole string. Dynamic Type comes for free because the run carries
  /// the text style and `adjustsFontForContentSizeCategory` is on.
  private static let baseFont = UIFont.preferredFont(forTextStyle: .body)

  /// Derives the UIKit `NSAttributedString` from the SwiftUI string for the given trait
  /// collection. Pure and trait-driven so the bridging test can exercise it off-screen in both
  /// a light and a dark/high-contrast collection. The `.link` runs carry across from the default
  /// bridge; the font, the hashtag bold trait, the foreground color, and the underline are
  /// overlaid because they either do not survive the default bridge into the UIKit attribute set
  /// (the SwiftUI underline lands under a SwiftUI-scope key, not `NSAttributedString.Key`) or do
  /// not resolve to a stable `UIColor` (`.primary`):
  ///   1. Overlay the base font across the whole string (the SwiftUI string carries no font).
  ///   2. Resolve hashtag bold from `inlinePresentationIntent == .stronglyEmphasized`.
  ///   3. Resolve every foreground color against the live trait collection so `.primary` and any
  ///      gamut-derived mention/hashtag/contact color render correctly in dark/high-contrast
  ///      rather than falling through to grey. The `.primary` slot maps to `UIColor.label`.
  ///   4. Re-apply the underline as the UIKit `.underlineStyle` for runs the SwiftUI string
  ///      underlined, since SwiftUI's underline does not bridge into the UIKit key.
  ///   5. Re-apply the self-mention `.backgroundColor` for runs that carry one, resolved against
  ///      the live trait collection. SwiftUI's `.backgroundColor` is a SwiftUI-scope attribute that
  ///      does not survive the default bridge into the UIKit key, so the highlight is lost without it.
  static func makeAttributedText(
    from attributedString: AttributedString,
    baseColor: Color,
    traitCollection: UITraitCollection
  ) -> NSAttributedString {
    let result = NSMutableAttributedString(attributedString)
    let fullRange = NSRange(location: 0, length: result.length)

    result.addAttribute(.font, value: baseFont, range: fullRange)

    for run in attributedString.runs {
      let nsRange = NSRange(run.range, in: attributedString)
      guard nsRange.length > 0 else { continue }

      let foreground = resolvedColor(run.foregroundColor ?? baseColor, traitCollection: traitCollection)
      result.addAttribute(.foregroundColor, value: foreground, range: nsRange)

      if run.inlinePresentationIntent?.contains(.stronglyEmphasized) == true {
        result.addAttribute(.font, value: boldFont(from: baseFont), range: nsRange)
      }

      if run.underlineStyle != nil {
        result.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: nsRange)
      }

      if let background = run.backgroundColor {
        result.addAttribute(
          .backgroundColor,
          value: resolvedColor(background, traitCollection: traitCollection),
          range: nsRange
        )
      }
    }

    return result
  }

  /// Resolves a SwiftUI `Color` to a `UIColor` for the live trait collection. SwiftUI's `.primary`
  /// does not bridge to a stable `UIColor`, so it is mapped to `UIColor.label` (matching
  /// `BaseColorSlot.swiftUIColor`'s `.primary`); every other color resolves directly.
  private static func resolvedColor(_ color: Color, traitCollection: UITraitCollection) -> UIColor {
    if color == .primary {
      return UIColor.label.resolvedColor(with: traitCollection)
    }
    return UIColor(color).resolvedColor(with: traitCollection)
  }

  /// Body text style metrics shared so the bold (hashtag) variant scales with Dynamic Type in
  /// lockstep with the surrounding `.body` runs, rather than freezing at a single point size.
  private static let bodyMetrics = UIFontMetrics(forTextStyle: .body)

  /// Adds the bold symbolic trait while preserving `.body` Dynamic Type scaling. Building the bold
  /// font at a fixed `pointSize` would strip the text-style metadata, so a hashtag run would not
  /// rescale with `adjustsFontForContentSizeCategory` while its neighbours do. Wrapping the bold
  /// descriptor in `UIFontMetrics(forTextStyle: .body)` keeps bold and body runs scaling together.
  private static func boldFont(from font: UIFont) -> UIFont {
    guard let descriptor = font.fontDescriptor.withSymbolicTraits(.traitBold) else {
      return font
    }
    return bodyMetrics.scaledFont(for: UIFont(descriptor: descriptor, size: 0))
  }

  /// Combines the SwiftUI string's hash with the Dynamic Type token. The SwiftUI string already
  /// folds in every contracted attribute (link, color, underline, bold), so its hash plus the
  /// category token fully discriminates the bridged output for the size cache.
  private static func contentHash(attributedString: AttributedString, contentSizeCategoryToken: String) -> Int {
    var hasher = Hasher()
    hasher.combine(attributedString)
    hasher.combine(contentSizeCategoryToken)
    return hasher.finalize()
  }

  // MARK: - Coordinator

  @MainActor
  final class Coordinator: NSObject {
    /// `(width, contentHash)` size cache, held here rather than in `@State` to keep the cache
    /// write off the SwiftUI layout path that this project's diffable crashes are tied to.
    var computedSizes: [SizeCacheKey: CGSize] = [:]

    /// Discriminator for the current bridged content plus Dynamic Type token; a change clears
    /// the size cache so a stale height cannot strand a reflowed bubble.
    var contentHash: Int = 0

    /// The live `OpenURLAction`, refreshed every `updateUIView` so a recycled cell taps through
    /// the current action (the one `MentionTapHandler` installs with its suppression gate).
    var openURL: OpenURLAction?

    func invalidateSizeCache() {
      computedSizes.removeAll(keepingCapacity: true)
    }

    /// Routes a tap on a linked glyph through the single injected `OpenURLAction`, so the
    /// suppression gate and mention/contact/channel/map routing stay unchanged. A tap that hits
    /// no link does nothing and is not consumed, so the bubble's own gestures still fire.
    @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
      guard let textView = recognizer.view as? BubbleBodyTextView,
            let url = textView.linkURL(at: recognizer.location(in: textView)) else { return }
      openURL?(url)
    }
  }
}

/// `(width, contentHash)` key for the body text view's size cache. `contentHash` discriminates the
/// bridged string plus the Dynamic Type token, so a width reuse across two different bodies, or the
/// same body at a new Dynamic Type size, cannot return a stale height.
struct SizeCacheKey: Hashable {
  let width: Double
  let contentHash: Int
}

/// Passive `UITextView` subclass for a message body. It empties `linkTextAttributes` so `.link`
/// runs render in their authored `.foregroundColor` rather than the view's `tintColor`, and it
/// resolves link taps itself via `linkURL(at:)` rather than installing UIKit's link interaction.
/// The tap recognizer added in `makeUIView` uses this view as its delegate (off Mac) so the
/// bubble's long-press wins: `shouldRecognizeSimultaneouslyWith` denies simultaneity with a
/// long-press, so a sustained press opens the actions sheet instead of firing a link tap, while the
/// table's pan recognizer is left untouched.
final class BubbleBodyTextView: UITextView, UIGestureRecognizerDelegate {
  /// Empties `linkTextAttributes` so `.link` runs render in their authored `.foregroundColor`
  /// rather than the view's `tintColor`. Applied from `makeUIView`: `init(usingTextLayoutManager:)`
  /// does not funnel through a `UITextView` designated-init override, so the policy is set here.
  func configureForBubble() {
    linkTextAttributes = [:]
  }

  /// The link URL at a point in the view, or nil if the point is not on a linked glyph. Resolves
  /// the glyph under the point with TextKit 1 and rejects the nearest-glyph over-hit that
  /// `glyphIndex(for:in:)` returns for a tap past end-of-line, in trailing whitespace, or below
  /// the last line, by requiring the point to lie inside that glyph's bounding rect.
  func linkURL(at point: CGPoint) -> URL? {
    guard textStorage.length > 0 else { return nil }

    let containerPoint = CGPoint(
      x: point.x - textContainerInset.left,
      y: point.y - textContainerInset.top
    )
    let glyphIndex = layoutManager.glyphIndex(for: containerPoint, in: textContainer)
    let glyphRect = layoutManager.boundingRect(
      forGlyphRange: NSRange(location: glyphIndex, length: 1),
      in: textContainer
    )
    guard glyphRect.contains(containerPoint) else { return nil }

    let charIndex = layoutManager.characterIndexForGlyph(at: glyphIndex)
    guard charIndex < textStorage.length else { return nil }

    switch textStorage.attribute(.link, at: charIndex, effectiveRange: nil) {
    case let url as URL: return url
    case let string as String: return URL(string: string)
    default: return nil
    }
  }

  func gestureRecognizer(
    _ gestureRecognizer: UIGestureRecognizer,
    shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
  ) -> Bool {
    !(otherGestureRecognizer is UILongPressGestureRecognizer)
  }
}
