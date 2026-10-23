import MC1Services
import SwiftUI
import UIKit

struct ContactNetworkPathSection: View {
  @Environment(\.appState) private var appState

  let state: ContactDetailState
  let onRowPosition: (ContactDetailRowPosition.Measurement) -> Void
  private var currentContact: ContactDTO {
    state.currentContact
  }

  private var pathViewModel: PathManagementViewModel {
    state.pathViewModel
  }

  private var pathDisplayWithNames: String {
    let pathData = currentContact.outPath
    let byteLength = currentContact.pathByteLength
    let hashSize = currentContact.pathHashSize
    guard byteLength > 0 else { return L10n.Contacts.Contacts.Route.direct }

    let relevantPath = pathData.prefix(byteLength)
    return stride(from: 0, to: relevantPath.count, by: hashSize).map { start in
      let end = min(start + hashSize, relevantPath.count)
      let hopBytes = Data(relevantPath[start..<end])
      if let name = pathViewModel.resolveHashToName(hopBytes) {
        return name
      }
      return hopBytes.uppercaseHexString()
    }.joined(separator: " \u{2192} ")
  }

  private func routeDisplayText(pathDisplay: String) -> String {
    if currentContact.isFloodRouted {
      L10n.Contacts.Contacts.Route.flood
    } else if currentContact.pathHopCount == 0 {
      L10n.Contacts.Contacts.Route.direct
    } else {
      pathDisplay
    }
  }

  private var networkPathFooterText: String {
    if currentContact.isFloodRouted {
      L10n.Contacts.Contacts.Detail.floodFooter
    } else {
      L10n.Contacts.Contacts.Detail.pathFooter
    }
  }

  private func pathAccessibilityLabel(pathDisplay: String) -> String {
    if currentContact.isFloodRouted {
      L10n.Contacts.Contacts.Detail.routeFlood
    } else if currentContact.pathHopCount == 0 {
      L10n.Contacts.Contacts.Detail.routeDirect
    } else {
      L10n.Contacts.Contacts.Detail.routePrefix(pathDisplay)
    }
  }

  var body: some View {
    let pathDisplay = pathDisplayWithNames
    let isRoutePopulated = !currentContact.isFloodRouted && currentContact.pathHopCount > 0
    let routeIDPrefixes = currentContact.pathNodesHex.joined(separator: ",")
    return Section {
      Label {
        VStack(alignment: .leading, spacing: 4) {
          Text(L10n.Contacts.Contacts.Detail.route)
            .font(.subheadline)
            .foregroundStyle(.secondary)

          Text(routeDisplayText(pathDisplay: pathDisplay))
            .font(.caption.monospaced())
            .foregroundStyle(.primary)
        }
      } icon: {
        Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
          .foregroundStyle(.secondary)
      }
      .accessibilityElement(children: .combine)
      .accessibilityLabel(pathAccessibilityLabel(pathDisplay: pathDisplay))
      .modifier(ContactDetailRowPosition(section: .outPath, onPosition: onRowPosition))
      .copyRouteContextMenu(route: routeIDPrefixes, enabled: isRoutePopulated)
      .accessibilityAction(named: L10n.Contacts.Contacts.Detail.copyRoute) {
        if isRoutePopulated { UIPasteboard.general.string = routeIDPrefixes }
      }

      // Show out-path hops for direct routing; inbound advert hops do not establish an out path.
      if !currentContact.isFloodRouted {
        Label {
          VStack(alignment: .leading, spacing: 4) {
            Text(L10n.Contacts.Contacts.Detail.hopsAway)
              .font(.subheadline)
              .foregroundStyle(.secondary)

            Text(currentContact.pathHopCount, format: .number)
              .font(.caption.monospaced())
              .foregroundStyle(.primary)
          }
        } icon: {
          Image(systemName: "arrowshape.bounce.right")
            .foregroundStyle(.secondary)
        }
      }

      if pathViewModel.isDiscovering {
        HStack {
          Label {
            VStack(alignment: .leading, spacing: 4) {
              Text(L10n.Contacts.Contacts.Detail.discoveringPath)
              if let remaining = pathViewModel.discoverySecondsRemaining, remaining > 0 {
                Text(L10n.Contacts.Contacts.Detail.secondsRemaining(remaining))
                  .font(.caption)
                  .foregroundStyle(.secondary)
              }
            }
          } icon: {
            Image(systemName: "antenna.radiowaves.left.and.right")
          }
          Spacer()
          ProgressView()
          Button(L10n.Contacts.Contacts.Common.cancel) {
            pathViewModel.cancelDiscovery()
          }
          .buttonStyle(.borderless)
          .font(.subheadline)
        }
      } else {
        Button {
          state.perform(.discoverPath)
        } label: {
          Label(L10n.Contacts.Contacts.Detail.discoverPath, systemImage: "antenna.radiowaves.left.and.right")
        }
        .radioDisabled(for: appState.connectionState)
      }

      Button {
        state.perform(.editPath)
      } label: {
        Label(L10n.Contacts.Contacts.Detail.editPath, systemImage: "pencil")
      }
      .radioDisabled(for: appState.connectionState)

      Button(role: .destructive) {
        state.perform(.resetPath)
      } label: {
        HStack {
          Label(L10n.Contacts.Contacts.Detail.resetPath, systemImage: "arrow.triangle.2.circlepath")
          if pathViewModel.isSettingPath {
            Spacer()
            ProgressView()
              .scaleEffect(0.8)
          }
        }
      }
      .radioDisabled(for: appState.connectionState, or: pathViewModel.isSettingPath || currentContact.isFloodRouted)
    } header: {
      Text(L10n.Contacts.Contacts.Detail.outboundPath)
    } footer: {
      Text(networkPathFooterText)
    }
  }
}

private extension View {
  /// Omits the menu when there is no out path to copy, avoiding an empty press-and-hold lift.
  @ViewBuilder
  func copyRouteContextMenu(route: String, enabled: Bool) -> some View {
    if enabled {
      contextMenu {
        Button {
          UIPasteboard.general.string = route
        } label: {
          Label(L10n.Contacts.Contacts.Detail.copyRoute, systemImage: "doc.on.doc")
        }
      }
    } else {
      self
    }
  }
}
