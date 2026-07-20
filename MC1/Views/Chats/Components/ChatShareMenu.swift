import CoreLocation
import MC1Services
import SwiftUI

/// Liquid Glass "+" menu for the chat input bar.
///
/// Each action produces a bare formatted string and hands it to `onInsert`; the
/// caller is responsible for appending it to the compose field. This view never
/// reads the compose text. The contact list is fetched lazily inside
/// `ShareContactPickerSheet`, so no contact array is built here.
struct ChatShareMenu: View {
  let onInsert: (String) -> Void

  @Environment(\.appState) private var appState

  @State private var isShowingContactPicker = false
  @State private var locationTask: Task<Void, Never>?

  /// Decimal-degree format for shared coordinates. Six fractional digits give
  /// roughly 0.1 m resolution, enough to round-trip a phone or node fix.
  private static let coordinateFormat = "%.6f, %.6f"

  /// Timeout for the on-tap location fix. Shorter than the service default
  /// because it runs in response to a user tap and should not stall the menu.
  private static let locationFixTimeout: Duration = .seconds(5)

  /// Already-published phone fix, only when location access is granted.
  private var publishedPhoneCoordinate: CLLocationCoordinate2D? {
    guard appState.locationService.isAuthorized,
          let location = appState.locationService.currentLocation else {
      return nil
    }
    return location.coordinate
  }

  /// Connected node's last-known coordinate, when it carries a valid fix.
  private var nodeCoordinate: CLLocationCoordinate2D? {
    guard let device = appState.connectedDevice, device.hasLocation else {
      return nil
    }
    return CLLocationCoordinate2D(latitude: device.latitude, longitude: device.longitude)
  }

  /// A coordinate is shareable when the phone or the node can supply one, or when location
  /// access is granted, since tapping then fetches a fresh fix via `requestCurrentLocation`.
  nonisolated static func canShareLocation(
    phoneCoordinate: CLLocationCoordinate2D?,
    nodeCoordinate: CLLocationCoordinate2D?,
    locationAuthorized: Bool
  ) -> Bool {
    phoneCoordinate != nil || nodeCoordinate != nil || locationAuthorized
  }

  private var canShareLocation: Bool {
    Self.canShareLocation(
      phoneCoordinate: publishedPhoneCoordinate,
      nodeCoordinate: nodeCoordinate,
      locationAuthorized: appState.locationService.isAuthorized
    )
  }

  /// Own info is shareable only with a full-length key and a name that is not blank, so the
  /// emitted share token always carries a usable, non-empty identity.
  nonisolated static func canShareMyInfo(publicKey: Data, nodeName: String) -> Bool {
    publicKey.count == ProtocolLimits.publicKeySize
      && !nodeName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  private var canShareMyInfo: Bool {
    guard let device = appState.connectedDevice else { return false }
    return Self.canShareMyInfo(publicKey: device.publicKey, nodeName: device.nodeName)
  }

  private var plusIconColor: Color {
    if #available(iOS 26.0, *) { .primary } else { Color(.systemGray) }
  }

  var body: some View {
    Menu {
      Button {
        shareLocation()
      } label: {
        Label(L10n.Chats.Chats.Share.location, systemImage: "location.fill")
      }
      .disabled(!canShareLocation)

      Button {
        isShowingContactPicker = true
      } label: {
        Label(L10n.Chats.Chats.Share.contact, systemImage: "person.crop.circle")
      }

      Button {
        shareMyInfo()
      } label: {
        Label(L10n.Chats.Chats.Share.myInfo, systemImage: "person.text.rectangle")
      }
      .disabled(!canShareMyInfo)
    } label: {
      Image(systemName: "plus")
        .font(.system(size: 18, weight: .semibold))
        .foregroundStyle(plusIconColor)
        .frame(width: ChatInputMetrics.controlHeight, height: ChatInputMetrics.controlHeight)
        .plusButtonBackground()
    }
    .buttonStyle(.plain)
    .accessibilityLabel(L10n.Chats.Chats.Input.ShareButton.accessibilityLabel)
    .sheet(isPresented: $isShowingContactPicker) {
      ShareContactPickerSheet(onInsert: onInsert)
    }
    .onDisappear { locationTask?.cancel() }
  }

  /// Shares a coordinate, preferring a fresh phone fix, then the last published
  /// phone fix, then the node's coordinate. `requestCurrentLocation` is only
  /// called when access is already granted, so no permission prompt can appear.
  private func shareLocation() {
    locationTask?.cancel()
    locationTask = Task {
      var fresh: CLLocationCoordinate2D?
      if appState.locationService.isAuthorized {
        fresh = await (try? appState.locationService
          .requestCurrentLocation(timeout: Self.locationFixTimeout))?.coordinate
      }
      // The menu may have been dismissed while awaiting the fix; do not write to a gone view.
      guard !Task.isCancelled else { return }
      guard let coordinate = fresh ?? publishedPhoneCoordinate ?? nodeCoordinate else { return }
      onInsert(String(format: Self.coordinateFormat, coordinate.latitude, coordinate.longitude))
    }
  }

  private func shareMyInfo() {
    guard let device = appState.connectedDevice,
          Self.canShareMyInfo(publicKey: device.publicKey, nodeName: device.nodeName) else { return }
    let token = ContactShareUtilities.formatShare(
      publicKey: device.publicKey,
      type: .chat,
      name: device.nodeName
    )
    onInsert(token)
  }
}

// MARK: - Platform-Conditional Styling

private extension View {
  @ViewBuilder
  func plusButtonBackground() -> some View {
    if #available(iOS 26.0, *) {
      glassEffect(.regular.interactive(), in: .circle)
    } else {
      background(Color(.systemGray5), in: Circle())
    }
  }
}

#Preview {
  ChatShareMenu(onInsert: { _ in })
    .padding()
}
