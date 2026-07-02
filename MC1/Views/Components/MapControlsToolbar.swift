import MapLibre
import SwiftUI

/// Shared liquid-glass toolbar hosting the controls every interactive map uses
/// (location, map options) plus a slot for one map-specific button.
/// The map options control is a native menu offering the north lock, map-style
/// picker, and the labels toggle.
struct MapControlsToolbar<AdditionalActions: View>: View {
  @Environment(\.appState) private var appState

  /// Centers the map on the user's location.
  var onLocationTap: () -> Void

  /// Whether the map is currently centered on the user; fills the location marker when true.
  var isCenteredOnUser: Bool = false

  @Binding var isNorthLocked: Bool
  @Binding var showLabels: Bool
  @Binding var mapStyleSelection: MapStyleSelection

  /// Current viewport, used to gate styles that lack offline coverage for the visible area.
  var viewportBounds: MLNCoordinateBounds?

  /// One map-specific button shown below the standard controls.
  @ViewBuilder var additionalActions: () -> AdditionalActions

  var body: some View {
    VStack(spacing: 0) {
      locationButton

      Divider()
        .frame(width: MapToolbarLayout.dividerWidth)

      CustomContentStack {
        additionalActions()
      }

      Divider()
        .frame(width: MapToolbarLayout.dividerWidth)

      mapOptionsMenu
    }
    .liquidGlass(in: .rect(cornerRadius: MapToolbarLayout.cornerRadius))
    .shadow(color: .black.opacity(0.15), radius: 4, y: 2)
    .padding()
  }

  // MARK: - Location Button

  private var locationButton: some View {
    Button(
      L10n.Map.Map.Controls.centerOnMyLocation,
      systemImage: isCenteredOnUser ? "location.fill" : "location",
      action: onLocationTap
    )
    .mapControlButton(tint: .primary)
  }

  // MARK: - Map Options Menu

  private var mapOptionsMenu: some View {
    Menu {
      Picker(L10n.Map.Map.Style.accessibilityLabel, selection: $mapStyleSelection) {
        ForEach(MapStyleSelection.allCases, id: \.self) { style in
          Text(style.label)
            .tag(style)
            .disabled(isDisabled(style))
        }
      }

      Divider()

      Toggle(L10n.Map.Map.Controls.showLabels, systemImage: "character.textbox", isOn: $showLabels)
      Toggle(L10n.Map.Map.Controls.lockNorth, systemImage: "location.north.line", isOn: $isNorthLocked)
    } label: {
      Label(L10n.Map.Map.Controls.mapOptions, systemImage: "ellipsis.circle")
    }
    .mapControlButton(tint: .primary)
  }

  private func isDisabled(_ style: MapStyleSelection) -> Bool {
    !appState.offlineMapService.isNetworkAvailable
      && (style.requiresNetwork || !hasOfflineCoverage(for: style))
  }

  private func hasOfflineCoverage(for style: MapStyleSelection) -> Bool {
    if let viewportBounds {
      appState.offlineMapService.hasCompletedPack(for: style.offlineMapLayer, overlapping: viewportBounds)
    } else {
      appState.offlineMapService.hasCompletedPack(for: style.offlineMapLayer)
    }
  }
}

// MARK: - Layout

private enum MapToolbarLayout {
  static var cornerRadius: CGFloat {
    if #available(iOS 26.0, *) { .infinity } else { 12 }
  }

  static var dividerWidth: CGFloat {
    if #available(iOS 26.0, *) { 0 } else { 24 }
  }
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
