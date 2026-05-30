import CoreLocation
import MC1Services
import SwiftUI

/// Display view for repeater stats, telemetry, and neighbors
struct RepeaterStatusView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    let session: RemoteNodeSessionDTO
    @State private var viewModel = RepeaterStatusViewModel()
    @State private var contacts: [ContactDTO] = []
    @State private var discoveredNodes: [DiscoveredNodeDTO] = []

    var body: some View {
        NavigationStack {
            List {
                makeHeaderSection()
                makeOwnerInfoSection()
                makeStatusSection()
                makeTelemetrySection()
                makeNeighborsSection()
                makeBatteryCurveSection()
            }
            .themedCanvas(theme)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.RemoteNodes.RemoteNodes.done) { dismiss() }
                }

                ToolbarItem(placement: .primaryAction) {
                    Button {
                        refresh()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Status.refresh)
                    .radioDisabled(
                        for: appState.connectionState,
                        or: viewModel.helper.isLoadingStatus || viewModel.isLoadingNeighbors || viewModel.helper.isLoadingTelemetry || viewModel.isLoadingOwnerInfo || viewModel.isDiscovering
                    )
                }

                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L10n.RemoteNodes.RemoteNodes.done) {
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.resignFirstResponder),
                            to: nil,
                            from: nil,
                            for: nil
                        )
                    }
                }
            }
            .task {
                viewModel.configure(appState: appState)
                await viewModel.registerHandlers(appState: appState)

                // Only request status on first load; user can refresh via toolbar/pull-to-refresh
                if viewModel.helper.status == nil {
                    await viewModel.requestStatus(for: session)
                }

                // Pre-load OCV settings and contacts for neighbor matching
                if let radioID = appState.connectedDevice?.radioID {
                    await viewModel.helper.loadOCVSettings(publicKey: session.publicKey, radioID: radioID)
                    if let dataStore = appState.services?.dataStore {
                        contacts = (try? await dataStore.fetchContacts(radioID: radioID)) ?? []
                        discoveredNodes = (try? await dataStore.fetchDiscoveredNodes(radioID: radioID)) ?? []
                    }
                }
            }
            .refreshable {
                await viewModel.requestStatus(for: session)
                // Refresh owner info only if already loaded
                if viewModel.ownerInfoLoaded {
                    await viewModel.requestOwnerInfo(for: session)
                }
                // Refresh telemetry only if already loaded
                if viewModel.helper.telemetryLoaded {
                    await viewModel.requestTelemetry(for: session)
                }
                // Refresh neighbors only if already loaded (skip during discovery polling)
                if viewModel.neighborsLoaded && !viewModel.isDiscovering {
                    await viewModel.requestNeighbors(for: session)
                }
            }
        }
        .onDisappear {
            viewModel.stopDiscovery()
        }
        .presentationDetents([.large])
    }

    // MARK: - Subviews

    private func makeHeaderSection() -> some View {
        NodeStatusHeaderSection(session: session)
    }

    private func makeOwnerInfoSection() -> some View {
        OwnerInfoSection(viewModel: viewModel, session: session)
    }

    private func makeStatusSection() -> some View {
        StatusSection(viewModel: viewModel)
    }

    private func makeNeighborsSection() -> some View {
        NeighborsSection(
            viewModel: viewModel,
            session: session,
            contacts: contacts,
            discoveredNodes: discoveredNodes,
            userLocation: appState.bestAvailableLocation,
            connectionState: appState.connectionState
        )
    }

    private func makeBatteryCurveSection() -> some View {
        NodeBatteryCurveDisclosureSection(
            helper: viewModel.helper,
            session: session,
            connectionState: appState.connectionState,
            connectedDeviceID: appState.connectedDevice?.radioID
        )
    }

    private func makeTelemetrySection() -> some View {
        NodeTelemetryDisclosureSection(helper: viewModel.helper) {
            await viewModel.requestTelemetry(for: session)
        }
    }

    // MARK: - Actions

    private func refresh() {
        Task {
            await viewModel.requestStatus(for: session)
            // Refresh owner info only if already loaded
            if viewModel.ownerInfoLoaded {
                await viewModel.requestOwnerInfo(for: session)
            }
            // Refresh telemetry only if already loaded
            if viewModel.helper.telemetryLoaded {
                await viewModel.requestTelemetry(for: session)
            }
            // Refresh neighbors only if already loaded (skip during discovery polling)
            if viewModel.neighborsLoaded && !viewModel.isDiscovering {
                await viewModel.requestNeighbors(for: session)
            }
        }
    }
}

// MARK: - Owner Info Section

private struct OwnerInfoSection: View {
    @Environment(\.appTheme) private var theme
    @Bindable var viewModel: RepeaterStatusViewModel
    let session: RemoteNodeSessionDTO

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
                Text(L10n.RemoteNodes.RemoteNodes.Status.ownerInfo)
            }
            .onChange(of: viewModel.ownerInfoExpanded) { _, isExpanded in
                if isExpanded && !viewModel.ownerInfoLoaded {
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

    var body: some View {
        NodeStatusSection(helper: viewModel.helper) {
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
    let connectionState: ConnectionState

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $viewModel.neighborsExpanded) {
                if viewModel.isLoadingNeighbors && !viewModel.isDiscovering {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
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
                        NavigationLink {
                            NeighborSNRChartView(
                                name: resolution?.displayName ?? L10n.RemoteNodes.RemoteNodes.Status.unknown,
                                neighborPrefix: neighbor.publicKeyPrefix,
                                fetchSnapshots: viewModel.helper.fetchHistory
                            )
                        } label: {
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
                }
            }
            .onChange(of: viewModel.neighborsExpanded) { _, isExpanded in
                if isExpanded && !viewModel.neighborsLoaded {
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

// MARK: - Neighbor SNR Chart

private struct NeighborSNRChartView: View {
    @Environment(\.appTheme) private var theme
    let name: String
    let neighborPrefix: Data
    let fetchSnapshots: @Sendable () async -> [NodeStatusSnapshotDTO]

    @State private var allDataPoints: [MetricChartView.DataPoint] = []
    @State private var timeRange: HistoryTimeRange = .all

    private var filteredDataPoints: [MetricChartView.DataPoint] {
        guard let start = timeRange.startDate else { return allDataPoints }
        return allDataPoints.filter { $0.date >= start }
    }

    var body: some View {
        List {
            HistoryTimeRangePicker(selection: $timeRange)

            Section {
                MetricChartView(
                    title: name,
                    unit: "dB",
                    dataPoints: filteredDataPoints,
                    accentColor: .blue
                )
            }
            .themedRowBackground(theme)
        }
        .themedCanvas(theme)
        .navigationTitle(name)
        .liquidGlassToolbarBackground()
        .task {
            let snapshots = await fetchSnapshots()
            allDataPoints = snapshots.compactMap { snapshot in
                guard let neighbors = snapshot.neighborSnapshots,
                      let match = neighbors.first(where: { $0.publicKeyPrefix == neighborPrefix })
                else { return nil }
                return MetricChartView.DataPoint(id: snapshot.id, date: snapshot.timestamp, value: match.snr)
            }
        }
    }
}

// MARK: - Neighbor Row

private struct NeighborRow: View {
    let neighbor: NeighbourInfo
    let displayName: String
    let matchKind: NodeNameMatchKind
    let previousNeighbor: NeighborSnapshotEntry?
    let hasPreviousSnapshot: Bool

    init(
        neighbor: NeighbourInfo,
        displayName: String,
        matchKind: NodeNameMatchKind,
        previousNeighbor: NeighborSnapshotEntry? = nil,
        hasPreviousSnapshot: Bool = false
    ) {
        self.neighbor = neighbor
        self.displayName = displayName
        self.matchKind = matchKind
        self.previousNeighbor = previousNeighbor
        self.hasPreviousSnapshot = hasPreviousSnapshot
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(displayName)

                    if hasPreviousSnapshot && previousNeighbor == nil {
                        Text(L10n.RemoteNodes.RemoteNodes.History.new)
                            .font(.caption2)
                            .bold()
                            .foregroundStyle(.green)
                    }

                    if matchKind == .fallback {
                        FallbackMatchIndicatorView(
                            accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.possibleMatch,
                            accessibilityHint: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation,
                            title: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchTitle,
                            explanation: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation
                        )
                    }
                }

                HStack(spacing: 4) {
                    Text(firstKeyByte)
                        .font(.system(.caption2, design: .monospaced))
                    Text("·")
                    Text(lastSeenText)
                        .font(.caption2)
                }
                .foregroundStyle(.secondary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Image(systemName: "cellularbars", variableValue: snrLevel)
                    .foregroundStyle(snrColor)

                Text(L10n.RemoteNodes.RemoteNodes.Status.snrFormat(neighbor.snr.formatted(.number.precision(.fractionLength(1)))))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if let previous = previousNeighbor {
                    let snrDelta = neighbor.snr - previous.snr
                    if abs(snrDelta) >= 0.1 {
                        StatusDeltaView(delta: snrDelta, higherIsBetter: true, unit: " dB", fractionDigits: 1)
                    }
                }
            }
        }
    }

    private var firstKeyByte: String {
        guard let firstByte = neighbor.publicKeyPrefix.first else { return "" }
        return Data([firstByte]).hexString()
    }

    private var lastSeenText: String {
        let seconds = neighbor.secondsAgo
        if seconds < 60 {
            return L10n.RemoteNodes.RemoteNodes.Status.secondsAgo(seconds)
        } else if seconds < 3600 {
            return L10n.RemoteNodes.RemoteNodes.Status.minutesAgo(seconds / 60)
        } else {
            return L10n.RemoteNodes.RemoteNodes.Status.hoursAgo(seconds / 3600)
        }
    }

    private var snrQuality: SNRQuality { SNRQuality(snr: neighbor.snr) }
    private var snrLevel: Double { snrQuality.barLevel }
    private var snrColor: Color { snrQuality.color }
}

// MARK: - Disappeared Neighbor Row

private struct DisappearedNeighborRow: View {
    let neighbor: NeighborSnapshotEntry
    let displayName: String
    let matchKind: NodeNameMatchKind

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(displayName)
                    if matchKind == .fallback {
                        FallbackMatchIndicatorView(
                            accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.possibleMatch,
                            accessibilityHint: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation,
                            title: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchTitle,
                            explanation: L10n.RemoteNodes.RemoteNodes.Status.possibleMatchExplanation
                        )
                    }
                }
                Text(L10n.RemoteNodes.RemoteNodes.History.notSeen)
                    .font(.caption2)
            }
            Spacer()
            Text(L10n.RemoteNodes.RemoteNodes.Status.snrFormat(neighbor.snr.formatted(.number.precision(.fractionLength(1)))))
                .font(.caption)
        }
        .foregroundStyle(.tertiary)
    }
}

#Preview {
    RepeaterStatusView(
        session: RemoteNodeSessionDTO(
            radioID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test Repeater",
            role: .repeater,
            isConnected: true,
            permissionLevel: .admin
        )
    )
    .environment(\.appState, AppState())
}
