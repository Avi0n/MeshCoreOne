import MapLibre
import SwiftUI

/// Single filter config for maps that expose the Filter menu.
struct MapFilterControl {
  let host: MapFilterHost
  var state: Binding<MapFilterState>
}

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

  var filter: MapFilterControl?

  /// One map-specific button shown below the standard controls.
  @ViewBuilder var additionalActions: () -> AdditionalActions

  var body: some View {
    VStack(spacing: 0) {
      locationButton

      if let filter {
        Divider()
          .frame(width: MapToolbarLayout.dividerWidth)
        filterMenu(state: filter.state, host: filter.host)
      }

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

  // MARK: - Filter Menu

  @ViewBuilder
  private func filterMenu(
    state: Binding<MapFilterState>,
    host: MapFilterHost
  ) -> some View {
    let capabilities = host.capabilities
    let active = state.wrappedValue.differsFromSeed(for: host)
    Menu {
      if capabilities.contains(.favorites) {
        Toggle(isOn: toggleBinding(state, \.favoritesOnly) { $0.setFavoritesOnly($1) }) {
          Label(L10n.Contacts.Contacts.Segment.favorites, systemImage: "star.fill")
        }
      }
      if capabilities.contains(.discovered) {
        Toggle(isOn: toggleBinding(state, \.showDiscovered) { $0.setShowDiscovered($1) }) {
          Label(L10n.Map.Map.Callout.discovered, systemImage: "antenna.radiowaves.left.and.right")
        }
        .disabled(state.wrappedValue.favoritesOnly)
      }
      if capabilities.includesTypes {
        Divider()
        Toggle(isOn: toggleBinding(state, \.showChat) {
          $0.setShowChat($1, host: host)
        }) {
          Text(L10n.Contacts.Contacts.Segment.contacts)
        }
        .disabled(state.wrappedValue.favoritesOnly)
        Toggle(isOn: toggleBinding(state, \.showRepeater) {
          $0.setShowRepeater($1, host: host)
        }) {
          Text(L10n.Contacts.Contacts.Segment.repeaters)
        }
        .disabled(state.wrappedValue.favoritesOnly)
        Toggle(isOn: toggleBinding(state, \.showRoom) {
          $0.setShowRoom($1, host: host)
        }) {
          Text(L10n.Contacts.Contacts.Segment.rooms)
        }
        .disabled(state.wrappedValue.favoritesOnly)
      }
    } label: {
      Label(
        L10n.Map.Map.Controls.filter,
        systemImage: active
          ? "line.3.horizontal.decrease.circle.fill"
          : "line.3.horizontal.decrease.circle"
      )
    }
    .mapControlButton(tint: .primary)
    .accessibilityLabel(L10n.Map.Map.Controls.filter)
    .accessibilityValue(active ? L10n.Map.Map.Controls.filterActive : "")
  }

  private func toggleBinding(
    _ state: Binding<MapFilterState>,
    _ keyPath: KeyPath<MapFilterState, Bool>,
    set: @escaping (inout MapFilterState, Bool) -> Void
  ) -> Binding<Bool> {
    Binding(
      get: { state.wrappedValue[keyPath: keyPath] },
      set: { newValue in
        var copy = state.wrappedValue
        set(&copy, newValue)
        state.wrappedValue = copy
      }
    )
  }

  // MARK: - Map Options Menu

  private var mapOptionsMenu: some View {
    Menu {
      Picker(L10n.Map.Map.Style.accessibilityLabel, selection: $mapStyleSelection) {
        ForEach(MapStyleSelection.allCases.reversed(), id: \.self) { style in
          Text(style.label)
            .tag(style)
            .disabled(isDisabled(style))
            .accessibilityHint(disabledReason(for: style) ?? "")
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

  private func disabledReason(for style: MapStyleSelection) -> String? {
    guard isDisabled(style) else { return nil }
    return style.requiresNetwork
      ? L10n.Map.Map.Style.requiresNetwork
      : L10n.Map.Map.Style.noOfflineCoverage
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
