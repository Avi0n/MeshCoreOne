import SwiftUI
import MC1Services

/// SF Symbol shown beside the send time when the sender's clock was invalid and the
/// app substituted a corrected value, signalling that the displayed time was adjusted.
private let correctedClockBadgeSymbol = "clock.badge.exclamationmark"

/// Renders the send-time / hop / path / region footer from a `MessageFooter`.
/// HStack at standard dynamic type sizes; VStack when
/// `dynamicTypeSize.isAccessibilitySize` is true. Each sub-row carries its own
/// `.accessibilityLabel(...)`; the container uses
/// `.accessibilityElement(children: .combine)` so VoiceOver surfaces the row as
/// a single rotor stop.
struct BubbleFooterRow: View {
    let footer: MessageFooter
    let dynamicTypeSize: DynamicTypeSize

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 2) {
                footerContents(allowsWrap: true)
            }
            .accessibilityElement(children: .combine)
        } else {
            HStack(spacing: 4) {
                footerContents(allowsWrap: false)
            }
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder
    private func footerContents(allowsWrap: Bool) -> some View {
        if let sendTime = footer.sendTimeToShow {
            BubbleSendTimeFooter(date: sendTime, wasCorrected: footer.sendTimeWasCorrected)
        }
        if footer.showHop {
            BubbleHopCountFooter(hopCount: footer.hopCount)
        }
        if let formattedPath = footer.formattedPath {
            BubblePathFooter(formattedPath: formattedPath)
        }
        if let region = footer.regionToShow {
            BubbleRegionFooter(regionName: region, allowsWrap: allowsWrap)
        }
    }
}

private struct BubbleSendTimeFooter: View {
    let date: Date
    let wasCorrected: Bool

    private var timeText: String {
        date.formatted(date: .omitted, time: .shortened)
    }

    private var accessibilityLabel: String {
        wasCorrected
            ? L10n.Chats.Chats.Message.SendTime.correctedAccessibilityLabel(timeText)
            : L10n.Chats.Chats.Message.SendTime.accessibilityLabel(timeText)
    }

    var body: some View {
        HStack(spacing: 4) {
            if wasCorrected {
                Image(systemName: correctedClockBadgeSymbol)
            }
            Text(timeText)
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }
}

private struct BubbleHopCountFooter: View {
    let hopCount: Int

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "arrowshape.bounce.right")
            Text("\(hopCount)")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Chats.Chats.Message.HopCount.accessibilityLabel(hopCount))
    }
}

private struct BubblePathFooter: View {
    let formattedPath: String

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
            Text(formattedPath)
        }
        .font(.caption2.monospaced())
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Chats.Chats.Message.Path.accessibilityLabel(formattedPath))
    }
}

private struct BubbleRegionFooter: View {
    let regionName: String
    let allowsWrap: Bool

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "globe")
            Text(regionName)
                .lineLimit(allowsWrap ? nil : 1)
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(L10n.Chats.Chats.Message.Region.accessibilityLabel(regionName))
    }
}
