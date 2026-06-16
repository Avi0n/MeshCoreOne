import CoreLocation
import MC1Services
import SwiftUI
import UIKit

/// Fragment-level view for the chat map thumbnail. A static cached snapshot with
/// the dropped pin. The whole card is the tap target and forwards the coordinate
/// to the same navigation sink as the coordinate text link. When the snapshot
/// failed to render, a retry control overlays the fallback so the user can
/// recover without waiting for the next offline-to-online edge.
struct MapPreviewFragmentView: View {
    let state: MapPreviewFragmentState
    let snapshotResolver: (MapSnapshotRequest) -> UIImage?
    let onTap: (CLLocationCoordinate2D) -> Void
    let onRequestSnapshot: (MapSnapshotRequest) -> Void
    let onRetry: (MapSnapshotRequest) -> Void

    private static let retryControlPadding: CGFloat = 8

    private var request: MapSnapshotRequest {
        MapSnapshotRequest(
            latitude: state.latitude,
            longitude: state.longitude,
            isDark: state.isDark,
            isOffline: state.isOffline
        )
    }

    private var coordinateText: String {
        state.coordinate.formattedString
    }

    /// True only when the render attempt resolved as a failure and the cache
    /// has no image — i.e., the `fallback` branch is on screen. The skeleton
    /// state is excluded so the retry icon never flashes during the initial
    /// render-in-progress window.
    private var isShowingFallback: Bool {
        state.isReady && snapshotResolver(request) == nil
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            content
                .frame(width: MapSnapshotLayout.width, height: MapSnapshotLayout.height)
                .clipShape(.rect(cornerRadius: MapSnapshotLayout.cornerRadius))
                .contentShape(Rectangle())
                .tapYieldingToLongPress { onTap(state.coordinate) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L10n.Map.Map.Preview.accessibilityLabel)
                .accessibilityValue(coordinateText)
                .accessibilityHint(L10n.Map.Map.Preview.accessibilityHint)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { onTap(state.coordinate) }

            if isShowingFallback {
                retryButton
                    .padding(Self.retryControlPadding)
            }
        }
    }

    private var retryButton: some View {
        Button {
            onRetry(request)
        } label: {
            Image(systemName: "arrow.clockwise.circle.fill")
                .font(.title2)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(.primary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.Map.Map.Preview.RetryButton.accessibilityLabel)
        .accessibilityAddTraits(.isButton)
    }

    @ViewBuilder
    private var content: some View {
        if let image = snapshotResolver(request) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
        } else {
            // No cached image: the fallback once the attempt resolved (a failed
            // render), otherwise the loading skeleton. Re-request in both cases —
            // `request()` is a no-op for cached/failed/in-flight, so this only does
            // work after a cache eviction dropped a previously rendered snapshot.
            // `.onAppear` lives on each branch so a flip between skeleton and
            // fallback (e.g. failure cleared by network recovery) re-fires the
            // request; SwiftUI fires `.onAppear` on a `Group` only once per Group
            // lifetime, not on inner-branch swaps.
            if state.isReady {
                fallback
                    .onAppear { onRequestSnapshot(request) }
            } else {
                skeleton
                    .onAppear { onRequestSnapshot(request) }
            }
        }
    }

    private var skeleton: some View {
        PreviewSkeleton(cornerRadius: MapSnapshotLayout.cornerRadius)
    }

    private var fallback: some View {
        ZStack {
            Color(.secondarySystemBackground)
            Image(systemName: "mappin.circle.fill")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
        }
        .accessibilityHidden(true)
    }
}
