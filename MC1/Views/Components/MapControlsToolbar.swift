import SwiftUI

/// Shared liquid-glass toolbar hosting the controls every interactive map uses
/// (north lock, location, layers, labels) plus a slot for one map-specific button.
struct MapControlsToolbar<TrailingContent: View>: View {
    /// Centers the map on the user's location.
    var onLocationTap: () -> Void

    @Binding var isNorthLocked: Bool
    @Binding var showLabels: Bool

    /// Controls layers menu visibility. Parent view handles menu presentation.
    @Binding var showingLayersMenu: Bool

    /// One map-specific button shown below the standard controls.
    @ViewBuilder var trailingContent: () -> TrailingContent

    var body: some View {
        VStack(spacing: 0) {
            northLockButton

            Divider()
                .frame(width: MapToolbarLayout.dividerWidth)

            locationButton

            Divider()
                .frame(width: MapToolbarLayout.dividerWidth)

            layersButton

            Divider()
                .frame(width: MapToolbarLayout.dividerWidth)

            labelsButton

            CustomContentStack {
                trailingContent()
            }
        }
        .liquidGlass(in: .rect(cornerRadius: MapToolbarLayout.cornerRadius))
        .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
        .padding()
    }

    // MARK: - North Lock Button

    private var northLockButton: some View {
        Button(
            isNorthLocked ? L10n.Map.Map.Controls.unlockNorth : L10n.Map.Map.Controls.lockNorth,
            systemImage: isNorthLocked ? "location.north.line.fill" : "location.north.line"
        ) {
            withAnimation {
                isNorthLocked.toggle()
            }
        }
        .mapControlButton(tint: isNorthLocked ? .blue : .primary)
    }

    // MARK: - Location Button

    private var locationButton: some View {
        Button(L10n.Map.Map.Controls.centerOnMyLocation, systemImage: "location.fill", action: onLocationTap)
            .mapControlButton(tint: .primary)
    }

    // MARK: - Layers Button

    private var layersButton: some View {
        Button(L10n.Map.Map.Controls.layers, systemImage: "square.3.layers.3d.down.right") {
            withAnimation(.spring(response: 0.3)) {
                showingLayersMenu.toggle()
            }
        }
        .mapControlButton(tint: .primary)
    }

    // MARK: - Labels Button

    private var labelsButton: some View {
        Button(
            showLabels ? L10n.Map.Map.Controls.hideLabels : L10n.Map.Map.Controls.showLabels,
            systemImage: "character.textbox"
        ) {
            withAnimation {
                showLabels.toggle()
            }
        }
        .mapControlButton(tint: showLabels ? .blue : .primary)
    }
}

// MARK: - Layout

private enum MapToolbarLayout {
    static let dividerWidth: CGFloat = 36
    static let cornerRadius: CGFloat = 8
}

// MARK: - Custom Content Stack

/// Wraps trailing content and inserts a divider before each child view.
private struct CustomContentStack<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        Group(subviews: content) { subviews in
            ForEach(subviews) { subview in
                Divider()
                    .frame(width: MapToolbarLayout.dividerWidth)
                subview
            }
        }
    }
}
