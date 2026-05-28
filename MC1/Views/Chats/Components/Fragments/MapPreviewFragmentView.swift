import CoreLocation
import MC1Services
import SwiftUI
import UIKit

/// Fragment-level view for the chat map thumbnail. A static cached snapshot with
/// the dropped pin. The whole card is the tap target and forwards the coordinate
/// to the same navigation sink as the coordinate text link.
struct MapPreviewFragmentView: View {
    let state: MapPreviewFragmentState
    let snapshotResolver: (MapSnapshotRequest) -> UIImage?
    let onTap: (CLLocationCoordinate2D) -> Void
    let onRequestSnapshot: (MapSnapshotRequest) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var request: MapSnapshotRequest {
        MapSnapshotRequest(
            latitude: state.latitude,
            longitude: state.longitude,
            isDark: state.isDark,
            isOffline: state.isOffline
        )
    }

    private var coordinateText: String {
        String(format: "%.5f, %.5f", state.latitude, state.longitude)
    }

    var body: some View {
        Button {
            onTap(state.coordinate)
        } label: {
            content
                .frame(width: MapSnapshotLayout.width, height: MapSnapshotLayout.height)
                .clipShape(.rect(cornerRadius: MapSnapshotLayout.cornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.Map.Map.Preview.accessibilityLabel)
        .accessibilityValue(coordinateText)
        .accessibilityHint(L10n.Map.Map.Preview.accessibilityHint)
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
            Group {
                if state.isReady {
                    fallback
                } else {
                    skeleton
                }
            }
            .onAppear { onRequestSnapshot(request) }
        }
    }

    private var skeleton: some View {
        RoundedRectangle(cornerRadius: MapSnapshotLayout.cornerRadius, style: .continuous)
            .fill(Color(.tertiarySystemFill))
            .modifier(Shimmer(isActive: !reduceMotion))
            .accessibilityHidden(true)
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
