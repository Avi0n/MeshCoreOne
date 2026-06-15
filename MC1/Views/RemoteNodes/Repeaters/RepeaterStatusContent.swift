import CoreLocation
import MC1Services
import SwiftUI

/// Stack-free repeater status body hosted by both the guest standalone sheet and the merged admin view.
///
/// The view model is owned as `@State` by the host. This content receives it as a plain `let` and must
/// never create, replace, or reset it; expand/loaded/loading/error and discovery state all live on the
/// view model so switching hosts or segments preserves it. The content view does not own the discovery
/// lifecycle either: only the guest host stops discovery on dismiss.
struct RepeaterStatusContent: View {
    @Environment(\.appTheme) private var theme

    let viewModel: RepeaterStatusViewModel
    let session: RemoteNodeSessionDTO
    let connectionState: DeviceConnectionState
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let userLocation: CLLocation?
    let connectedDeviceID: UUID?
    /// Contact whose login route is shown at the bottom; nil hides the route section.
    var routePathContact: ContactDTO?

    var body: some View {
        List {
            NodeStatusHeaderSection(session: session)
            StatusSection(viewModel: viewModel, session: session, connectionState: connectionState)
            NodeTelemetryDisclosureSection(helper: viewModel.helper, connectionState: connectionState) {
                await viewModel.requestTelemetry(for: session)
            }
            NeighborsSection(
                viewModel: viewModel,
                session: session,
                contacts: contacts,
                discoveredNodes: discoveredNodes,
                userLocation: userLocation,
                connectionState: connectionState
            )
            OwnerInfoSection(viewModel: viewModel, session: session, connectionState: connectionState)
            NodeBatteryCurveDisclosureSection(
                helper: viewModel.helper,
                session: session,
                connectionState: connectionState,
                connectedDeviceID: connectedDeviceID
            )
            if let routePathContact {
                NodeRoutePathSection(
                    contact: routePathContact,
                    contacts: contacts,
                    discoveredNodes: discoveredNodes,
                    userLocation: userLocation
                )
            }
        }
        .nodeStatusDestinations(helper: viewModel.helper)
        .themedCanvas(theme)
        .scrollDismissesKeyboard(.interactively)
    }
}

// MARK: - Owner Info Section

private struct OwnerInfoSection: View {
    @Environment(\.appTheme) private var theme
    @Bindable var viewModel: RepeaterStatusViewModel
    let session: RemoteNodeSessionDTO
    let connectionState: DeviceConnectionState

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $viewModel.ownerInfoExpanded) {
                if viewModel.isLoadingOwnerInfo {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                } else if let error = viewModel.ownerInfoError {
                    Text(error)
                        .foregroundStyle(.red)
                } else if let info = viewModel.ownerInfo, !info.isEmpty {
                    Text(info)
                } else {
                    Text(L10n.RemoteNodes.RemoteNodes.Status.noOwnerInfo)
                        .foregroundStyle(.secondary)
                }
            } label: {
                HStack {
                    Text(L10n.RemoteNodes.RemoteNodes.Status.ownerInfo)
                    Spacer()
                    SectionReloadButton(
                        isLoading: viewModel.isLoadingOwnerInfo,
                        isLoaded: viewModel.ownerInfoLoaded,
                        hasError: viewModel.ownerInfoError != nil,
                        isDisabled: connectionState != .ready,
                        accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.Accessibility.reloadOwnerInfo,
                        onReload: { await viewModel.requestOwnerInfo(for: session) }
                    )
                }
            }
            .onChange(of: viewModel.ownerInfoExpanded) { _, isExpanded in
                if isExpanded && !viewModel.ownerInfoLoaded && !viewModel.isLoadingOwnerInfo {
                    Task {
                        await viewModel.requestOwnerInfo(for: session)
                    }
                }
            }
        }
        .themedRowBackground(theme)
    }
}

// MARK: - Status Section

private struct StatusSection: View {
    let viewModel: RepeaterStatusViewModel
    let session: RemoteNodeSessionDTO
    let connectionState: DeviceConnectionState

    var body: some View {
        NodeStatusSection(helper: viewModel.helper, connectionState: connectionState) {
            await viewModel.requestStatus(for: session)
        } rows: {
            StatusRows(viewModel: viewModel)
        }
    }
}

// MARK: - Status Rows

private struct StatusRows: View {
    let viewModel: RepeaterStatusViewModel

    var body: some View {
        NodeCommonStatusRows(helper: viewModel.helper)

        if let receiveErrors = viewModel.receiveErrorsDisplay {
            LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.receiveErrors, value: receiveErrors)
        }
    }
}

// MARK: - Neighbors Section

private struct NeighborsSection: View {
    @Environment(\.appTheme) private var theme
    @Bindable var viewModel: RepeaterStatusViewModel
    let session: RemoteNodeSessionDTO
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let userLocation: CLLocation?
    let connectionState: DeviceConnectionState

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $viewModel.neighborsExpanded) {
                if viewModel.isLoadingNeighbors && !viewModel.isDiscovering {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                } else if let error = viewModel.neighborsSectionError, !viewModel.isDiscovering {
                    Text(error)
                        .foregroundStyle(.red)
                } else if viewModel.neighbors.isEmpty && !viewModel.isDiscovering {
                    Text(L10n.RemoteNodes.RemoteNodes.Status.noNeighbors)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(viewModel.neighbors, id: \.publicKeyPrefix) { neighbor in
                        let resolution = NeighborNameResolver.resolve(
                            for: neighbor.publicKeyPrefix,
                            contacts: contacts,
                            discoveredNodes: discoveredNodes,
                            userLocation: userLocation
                        )
                        NavigationLink(value: NodeStatusRoute.neighborChart(
                            name: resolution?.displayName ?? L10n.RemoteNodes.RemoteNodes.Status.unknown,
                            neighborPrefix: neighbor.publicKeyPrefix
                        )) {
                            NeighborRow(
                                neighbor: neighbor,
                                displayName: resolution?.displayName ?? L10n.RemoteNodes.RemoteNodes.Status.unknown,
                                matchKind: resolution?.matchKind ?? .unresolved,
                                previousNeighbor: viewModel.helper.previousSnapshot?.neighborSnapshots?.first {
                                    $0.publicKeyPrefix == neighbor.publicKeyPrefix
                                },
                                hasPreviousSnapshot: viewModel.helper.previousSnapshot?.neighborSnapshots != nil
                            )
                        }
                    }

                    if let previousNeighbors = viewModel.helper.previousSnapshot?.neighborSnapshots {
                        let currentPrefixes = Set(viewModel.neighbors.map(\.publicKeyPrefix))
                        let disappeared = previousNeighbors.filter { !currentPrefixes.contains($0.publicKeyPrefix) }
                        ForEach(disappeared, id: \.publicKeyPrefix) { old in
                            let resolution = NeighborNameResolver.resolve(
                                for: old.publicKeyPrefix,
                                contacts: contacts,
                                discoveredNodes: discoveredNodes,
                                userLocation: userLocation
                            )
                            DisappearedNeighborRow(
                                neighbor: old,
                                displayName: resolution?.displayName ?? NeighborNameResolver.fallbackName(for: old.publicKeyPrefix),
                                matchKind: resolution?.matchKind ?? .unresolved
                            )
                        }
                    }
                }

                if session.isAdmin {
                    Button {
                        if viewModel.isDiscovering {
                            viewModel.stopDiscovery()
                        } else {
                            viewModel.startDiscovery(for: session)
                        }
                    } label: {
                        HStack {
                            if viewModel.isDiscovering {
                                ProgressView()
                                    .controlSize(.small)
                                Text(L10n.RemoteNodes.RemoteNodes.Status.discoveringSeconds(viewModel.discoverySecondsRemaining))
                            } else {
                                Label(L10n.RemoteNodes.RemoteNodes.Status.discoverNeighbors, systemImage: "antenna.radiowaves.left.and.right")
                            }
                        }
                    }
                    .radioDisabled(for: connectionState, or: viewModel.isLoadingNeighbors && !viewModel.isDiscovering)
                }
            } label: {
                HStack {
                    Text(L10n.RemoteNodes.RemoteNodes.Status.neighbors)
                    Spacer()
                    if viewModel.neighborsLoaded {
                        Text("\(viewModel.neighbors.count)")
                            .foregroundStyle(.secondary)
                    }
                    SectionReloadButton(
                        isLoading: viewModel.isLoadingNeighbors && !viewModel.isDiscovering,
                        isLoaded: viewModel.neighborsLoaded,
                        hasError: viewModel.neighborsSectionError != nil,
                        isDisabled: connectionState != .ready || viewModel.isDiscovering,
                        accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.Accessibility.reloadNeighbors,
                        onReload: { await viewModel.requestNeighbors(for: session) }
                    )
                }
            }
            .onChange(of: viewModel.neighborsExpanded) { _, isExpanded in
                if isExpanded && !viewModel.neighborsLoaded && !viewModel.isLoadingNeighbors {
                    Task {
                        await viewModel.requestNeighbors(for: session)
                    }
                }
            }
        } footer: {
            Text(L10n.RemoteNodes.RemoteNodes.Status.neighborsFooter)
        }
        .themedRowBackground(theme)
    }
}
