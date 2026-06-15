import CoreLocation
import MC1Services
import SwiftUI

/// Read-only display of the route used to reach a node: a collapsed summary that expands into the
/// resolved repeater name for each hop. Mirrors the login sheet's path section without the routing
/// toggle. Names are resolved synchronously from the contact/discovered-node lists the host already
/// holds, so this is a dumb leaf with no view model of its own.
struct NodeRoutePathSection: View {
    @Environment(\.appTheme) private var theme
    let contact: ContactDTO
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let userLocation: CLLocation?

    @State private var isExpanded = false

    private var resolvedHops: [ResolvedPathHop] {
        NeighborNameResolver.resolvePath(
            contact.pathHops,
            contacts: contacts,
            discoveredNodes: discoveredNodes,
            userLocation: userLocation
        )
    }

    var body: some View {
        Section {
            content
        } header: {
            Text(L10n.RemoteNodes.RemoteNodes.Auth.path)
        }
        .themedRowBackground(theme)
    }

    @ViewBuilder
    private var content: some View {
        if contact.isFloodRouted {
            Label {
                Text(L10n.RemoteNodes.RemoteNodes.Auth.noRouteSet)
                    .foregroundStyle(.secondary)
            } icon: {
                Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        } else {
            let hops = resolvedHops
            if hops.isEmpty {
                NodePathSummaryLabel(contact: contact)
            } else {
                DisclosureGroup(isExpanded: $isExpanded) {
                    ForEach(hops) { hop in
                        NodePathHopRow(hex: hop.hex, resolution: hop.resolution)
                    }
                } label: {
                    NodePathSummaryLabel(contact: contact)
                }
            }
        }
    }
}

// MARK: - Path Summary Label

/// The collapsed one-line route summary (`A3 → 7F → 42`, or `Direct`).
struct NodePathSummaryLabel: View {
    let contact: ContactDTO

    private var pathDisplayText: String {
        contact.pathHopCount == 0 ? L10n.Contacts.Contacts.Route.direct : contact.pathString
    }

    private var pathAccessibilityLabel: String {
        contact.pathHopCount == 0
            ? L10n.Contacts.Contacts.Detail.routeDirect
            : L10n.Contacts.Contacts.Detail.routePrefix(pathDisplayText)
    }

    var body: some View {
        Label {
            Text(pathDisplayText)
                .font(.caption.monospaced())
                .foregroundStyle(.primary)
                .lineLimit(nil)
        } icon: {
            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
        .accessibilityLabel(pathAccessibilityLabel)
    }
}

// MARK: - Path Hop Row

/// A single expanded hop: hash hex plus the resolved repeater name, with a possible-match indicator
/// when the name was matched only by a short prefix.
struct NodePathHopRow: View {
    let hex: String
    let resolution: NodeNameResolution

    var body: some View {
        HStack(spacing: 6) {
            Text(hex)
                .font(.body.monospaced())
                .foregroundStyle(.secondary)

            Text(resolution.displayName)

            if resolution.matchKind == .fallback {
                FallbackMatchIndicatorView(
                    accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.possibleMatch,
                    accessibilityHint: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation,
                    title: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchTitle,
                    explanation: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation
                )
            }
        }
        .accessibilityElement(children: .combine)
    }
}
