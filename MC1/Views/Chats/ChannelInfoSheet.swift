import SwiftUI
import MC1Services
import CoreImage.CIFilterBuiltins
import OSLog

private let logger = Logger(subsystem: "com.mc1", category: "ChannelInfoSheet")

/// Sheet displaying channel info with sharing and deletion options
struct ChannelInfoSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.chatViewModel) private var viewModel

    let channel: ChannelDTO
    let onClearMessages: () -> Void
    let onDelete: () -> Void

    @State private var notificationLevel: NotificationLevel
    @State private var isFavorite: Bool
    @State private var isDeleting = false
    @State private var isClearingMessages = false
    @State private var showingDeleteConfirmation = false
    @State private var showingClearMessagesConfirmation = false
    @State private var errorMessage: String?
    @State private var copyHapticTrigger = 0
    @State private var notificationTask: Task<Void, Never>?
    @State private var favoriteTask: Task<Void, Never>?
    @State private var knownRegions: [String] = []
    @State private var isRegionExpanded = false
    @State private var isDiscoveringRegions = false
    @State private var discoveryMessage: String?
    @State private var showingRegionManagement = false
    @State private var discoveryTask: Task<Void, Never>?
    @State private var discoveredNewRegions: [String] = []
    @State private var showingDiscoveryResults = false
    @State private var selectedRegionScope: String?
    @State private var hasLoadedRegions = false

    init(channel: ChannelDTO, onClearMessages: @escaping () -> Void, onDelete: @escaping () -> Void) {
        self.channel = channel
        self.onClearMessages = onClearMessages
        self.onDelete = onDelete
        self._notificationLevel = State(initialValue: channel.notificationLevel)
        self._isFavorite = State(initialValue: channel.isFavorite)
        self._selectedRegionScope = State(initialValue: channel.regionScope)
    }

    var body: some View {
        NavigationStack {
            Form {
                // Channel Header Section
                ChannelInfoHeaderSection(channel: channel)

                // Quick Actions Section
                ConversationQuickActionsSection(
                    notificationLevel: $notificationLevel,
                    isFavorite: $isFavorite,
                    availableLevels: NotificationLevel.channelLevels
                )
                .onChange(of: notificationLevel) { _, newValue in
                    notificationTask?.cancel()
                    notificationTask = Task {
                        await viewModel?.setNotificationLevel(.channel(channel), level: newValue)
                    }
                }
                .onChange(of: isFavorite) { _, newValue in
                    favoriteTask?.cancel()
                    favoriteTask = Task {
                        await viewModel?.setFavorite(.channel(channel), isFavorite: newValue)
                    }
                }
                .onDisappear {
                    notificationTask?.cancel()
                    favoriteTask?.cancel()
                    discoveryTask?.cancel()
                }

                // Region Scope Section
                ChannelInfoRegionSection(
                    knownRegions: knownRegions,
                    selectedRegionScope: selectedRegionScope,
                    isExpanded: $isRegionExpanded,
                    isDiscovering: $isDiscoveringRegions,
                    discoveryMessage: $discoveryMessage,
                    onRegionSelected: { region in
                        Task { await selectRegion(region) }
                    },
                    onDiscoverTapped: {
                        runDiscovery { newRegions in
                            for region in newRegions { await addRegion(region) }
                        }
                    },
                    onManageTapped: {
                        showingRegionManagement = true
                    }
                )

                // QR Code Section (only for private channels with secrets)
                if channel.hasSecret && !channel.isPublicChannel {
                    ChannelInfoQRCodeSection(channel: channel)
                }

                // Secret Key Section (only for private channels)
                if channel.hasSecret && !channel.isPublicChannel {
                    ChannelInfoSecretKeySection(
                        channel: channel,
                        copyHapticTrigger: $copyHapticTrigger
                    )
                }

                // Error Section
                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                        Button(L10n.Localizable.Common.tryAgain) {
                            Task { await deleteChannel() }
                        }
                    }
                }

                // Actions Section
                ChannelInfoActionsSection(
                    isActionInProgress: isDeleting || isClearingMessages,
                    isClearingMessages: isClearingMessages,
                    isDeleting: isDeleting,
                    showingClearMessagesConfirmation: $showingClearMessagesConfirmation,
                    showingDeleteConfirmation: $showingDeleteConfirmation
                )
            }
            .navigationTitle(L10n.Chats.Chats.ChannelInfo.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Chats.Chats.Common.done) {
                        dismiss()
                    }
                }
            }
            .navigationDestination(isPresented: $showingRegionManagement) {
                RegionManagementView(
                    knownRegions: $knownRegions,
                    isDiscovering: $isDiscoveringRegions,
                    discoveryMessage: $discoveryMessage,
                    onRemoveRegion: { region in
                        Task { await removeRegion(region) }
                    },
                    onAddRegion: { region in
                        Task { await addRegion(region) }
                    },
                    onDiscoverTapped: {
                        runDiscovery { newRegions in
                            discoveredNewRegions = newRegions
                            showingDiscoveryResults = true
                        }
                    }
                )
            }
            .navigationDestination(isPresented: $showingDiscoveryResults) {
                RegionDiscoveryResultsView(discoveredRegions: discoveredNewRegions) { selected in
                    Task {
                        for region in selected {
                            await addRegion(region)
                        }
                    }
                }
            }
            .task {
                guard !hasLoadedRegions else { return }
                hasLoadedRegions = true
                if let device = try? await appState.offlineDataStore?.fetchDevice(radioID: channel.radioID) {
                    knownRegions = device.knownRegions
                }
            }
        }
        .confirmationDialog(
            L10n.Chats.Chats.ChannelInfo.ClearMessagesConfirm.title,
            isPresented: $showingClearMessagesConfirmation,
            titleVisibility: .visible
        ) {
            Button(L10n.Chats.Chats.ChannelInfo.clearMessagesButton, role: .destructive) {
                Task {
                    await clearMessages()
                }
            }
            Button(L10n.Chats.Chats.Common.cancel, role: .cancel) {}
        } message: {
            Text(L10n.Chats.Chats.ChannelInfo.ClearMessagesConfirm.message)
        }
        .confirmationDialog(
            L10n.Chats.Chats.ChannelInfo.DeleteConfirm.title,
            isPresented: $showingDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(L10n.Chats.Chats.ChannelInfo.deleteButton, role: .destructive) {
                Task {
                    await deleteChannel()
                }
            }
            Button(L10n.Chats.Chats.Common.cancel, role: .cancel) {}
        } message: {
            Text(L10n.Chats.Chats.ChannelInfo.DeleteConfirm.message)
        }
        .sensoryFeedback(.success, trigger: copyHapticTrigger)
    }

    // MARK: - Private Methods

    private func clearNotificationsForChannel(radioID: UUID) async {
        await appState.services?.notificationService.removeDeliveredNotifications(
            forChannelIndex: channel.index,
            radioID: radioID
        )
        await appState.services?.notificationService.updateBadgeCount()
    }

    private func deleteChannel() async {
        guard let radioID = appState.connectedDevice?.radioID else {
            errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
            return
        }

        guard let channelService = appState.services?.channelService else {
            errorMessage = L10n.Chats.Chats.Error.servicesUnavailable
            return
        }

        isDeleting = true
        errorMessage = nil

        do {
            // Clear channel on device (sends empty name + zero secret via BLE)
            // and deletes from local database
            try await channelService.clearChannel(
                radioID: radioID,
                index: channel.index
            )

            await clearNotificationsForChannel(radioID: radioID)

            dismiss()
            onDelete()
        } catch {
            errorMessage = error.localizedDescription
            isDeleting = false
        }
    }

    private func clearMessages() async {
        guard let radioID = appState.connectedDevice?.radioID else {
            errorMessage = L10n.Chats.Chats.Error.noDeviceConnected
            return
        }

        guard let channelService = appState.services?.channelService else {
            errorMessage = L10n.Chats.Chats.Error.servicesUnavailable
            return
        }

        isClearingMessages = true
        errorMessage = nil

        do {
            try await channelService.clearChannelMessages(
                radioID: radioID,
                channelIndex: channel.index
            )

            await clearNotificationsForChannel(radioID: radioID)

            onClearMessages()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
            isClearingMessages = false
        }
    }

    private func selectRegion(_ region: String?) async {
        let previousScope = selectedRegionScope
        selectedRegionScope = region
        do {
            try await appState.offlineDataStore?.setChannelRegionScope(channel.id, regionScope: region)
        } catch {
            logger.error("Failed to save region scope: \(error.localizedDescription)")
            selectedRegionScope = previousScope
            return
        }

        if let session = appState.services?.session {
            let scope: FloodScope = region.map { .region($0) } ?? .disabled
            try? await session.setFloodScope(scope)
        }
    }

    private func removeRegion(_ region: String) async {
        do {
            try await appState.offlineDataStore?.removeDeviceKnownRegion(radioID: channel.radioID, region: region)
            knownRegions.removeAll { $0 == region }
        } catch {
            logger.error("Failed to remove region: \(error.localizedDescription)")
        }
    }

    private func addRegion(_ region: String) async {
        do {
            try await appState.offlineDataStore?.addDeviceKnownRegion(radioID: channel.radioID, region: region)
            if !knownRegions.contains(region) {
                knownRegions.append(region)
            }
        } catch {
            logger.error("Failed to add region: \(error.localizedDescription)")
        }
    }

    private func runDiscovery(onNewRegions: @escaping ([String]) async -> Void) {
        discoveryTask?.cancel()
        discoveryTask = Task {
            isDiscoveringRegions = true
            discoveryMessage = nil

            let newRegions = await discoverNewRegions()

            guard !Task.isCancelled else {
                isDiscoveringRegions = false
                return
            }

            if newRegions.isEmpty {
                if discoveryMessage == nil {
                    discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noNewRegions
                }
            } else {
                await onNewRegions(newRegions)
            }
            isDiscoveringRegions = false
        }
    }

    /// Broadcasts a discover probe to find nearby repeaters, then queries only those for regions
    private func discoverNewRegions() async -> [String] {
        guard let session = appState.services?.session,
              let contactService = appState.services?.contactService else {
            return []
        }
        let radioID = channel.radioID

        // Broadcast a DISCOVER_REQ and collect the public keys of repeaters that respond
        let discoveredPubkeys: Set<Data>
        do {
            let tag = try await session.sendNodeDiscoverRequest(
                filter: NodeDiscoveryFilter.repeaters.filterValue,
                prefixOnly: false
            )
            let tagData = withUnsafeBytes(of: tag.littleEndian) { Data($0) }

            let (subscriptionID, events) = await session.eventsTracked()
            let listenTask = Task { () -> Set<Data> in
                var keys = Set<Data>()
                for await event in events {
                    if case .discoverResponse(let response) = event,
                       response.tag == tagData {
                        keys.insert(response.publicKey)
                    }
                }
                return keys
            }

            try? await Task.sleep(for: Self.regionDiscoveryListenDuration)
            await session.finishEvents(id: subscriptionID)
            discoveredPubkeys = await listenTask.value
        } catch {
            return []
        }

        guard !Task.isCancelled else { return [] }

        if discoveredPubkeys.isEmpty {
            discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded
            return []
        }

        // Build the query pool from both contacts and the discovered-nodes table
        // so we can query repeaters the user hasn't added as contacts yet.
        let queryTargets: [MeshContact]
        do {
            let contacts = try await contactService.getContacts(radioID: radioID)
            let discoveredNodes = (try? await appState.offlineDataStore?.fetchDiscoveredNodes(radioID: radioID)) ?? []
            queryTargets = Self.buildRegionQueryTargets(
                responders: discoveredPubkeys,
                contacts: contacts,
                discoveredNodes: discoveredNodes
            )
        } catch {
            discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errLoadingRepeaters
            return []
        }

        if queryTargets.isEmpty {
            discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded
            return []
        }

        var allRegions = Set<String>()
        var anyTableFull = false
        let tableFullCode = Self.firmwareTableFullErrorCode

        await withTaskGroup(of: RegionQueryOutcome.self) { group in
            for meshContact in queryTargets {
                guard !Task.isCancelled else { break }
                group.addTask {
                    do {
                        let regions = try await session.requestRegions(from: meshContact)
                        return .regions(regions)
                    } catch let MeshCoreError.deviceError(code) where code == tableFullCode {
                        return .tableFull
                    } catch {
                        return .otherFailure
                    }
                }
            }
            for await outcome in group {
                switch outcome {
                case .regions(let regions):
                    allRegions.formUnion(regions)
                case .tableFull:
                    anyTableFull = true
                case .otherFailure:
                    break
                }
            }
        }

        let knownSet = Set(knownRegions)
        let newRegions = allRegions.subtracting(knownSet).sorted()
        if newRegions.isEmpty && anyTableFull {
            discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errRadioContactsFull
        }
        return newRegions
    }

    private enum RegionQueryOutcome: Sendable {
        case regions([String])
        case tableFull
        case otherFailure
    }

    // Firmware error code for "contact table full" responses (`ERR_CODE_TABLE_FULL`).
    private static let firmwareTableFullErrorCode: UInt8 = 3

    // Matches NodeDiscoveryViewModel.scanDuration so flood-routed responses from distant
    // mesh nodes have time to arrive.
    private static let regionDiscoveryListenDuration: Duration = .seconds(15)

    /// Builds the `MeshContact` query pool used to fetch regions from each responder.
    /// Prefers contact records (they carry direct routing data when available) and fills
    /// in from the discovered-nodes table for responders the user has not added as contacts.
    static func buildRegionQueryTargets(
        responders: Set<Data>,
        contacts: [ContactDTO],
        discoveredNodes: [DiscoveredNodeDTO]
    ) -> [MeshContact] {
        var byKey: [Data: MeshContact] = [:]
        for contact in contacts where contact.type == .repeater && responders.contains(contact.publicKey) {
            byKey[contact.publicKey] = contact.toContactFrame().toMeshContact()
        }
        for node in discoveredNodes where node.nodeType == .repeater
            && responders.contains(node.publicKey)
            && byKey[node.publicKey] == nil {
            let frame = ContactFrame(
                publicKey: node.publicKey,
                type: node.nodeType,
                flags: 0,
                outPathLength: node.outPathLength,
                outPath: node.outPath,
                name: node.name,
                lastAdvertTimestamp: node.lastAdvertTimestamp,
                latitude: node.latitude,
                longitude: node.longitude,
                lastModified: 0
            )
            byKey[node.publicKey] = frame.toMeshContact()
        }
        return Array(byKey.values)
    }
}

// MARK: - Extracted Views

private struct ChannelInfoHeaderSection: View {
    let channel: ChannelDTO

    private var channelTypeLabel: String {
        if channel.isPublicChannel {
            return L10n.Chats.Chats.ChannelInfo.ChannelType.`public`
        } else if channel.name.hasPrefix("#") {
            return L10n.Chats.Chats.ChannelInfo.ChannelType.hashtag
        } else {
            return L10n.Chats.Chats.ChannelInfo.ChannelType.`private`
        }
    }

    var body: some View {
        Section {
            HStack {
                Spacer()
                VStack(spacing: 12) {
                    ChannelAvatar(channel: channel, size: 80)

                    Text(channel.displayName)
                        .font(.title2)
                        .bold()

                    Text(channelTypeLabel)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .listRowBackground(Color.clear)
        }
    }
}

private struct ChannelInfoQRCodeSection: View {
    let channel: ChannelDTO

    @State private var qrImage: UIImage?

    var body: some View {
        Section {
            HStack {
                Spacer()
                VStack(spacing: 12) {
                    if let qrImage {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 180, height: 180)
                    }

                    Text(L10n.Chats.Chats.ChannelInfo.scanToJoin)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
        } header: {
            Text(L10n.Chats.Chats.ChannelInfo.shareChannel)
        }
        .task {
            qrImage = generateQRCode()
        }
    }

    private func generateQRCode() -> UIImage? {
        // Format: meshcore://channel/add?name=<name>&secret=<hex>
        let urlString = "meshcore://channel/add?name=\(channel.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")&secret=\(channel.secret.hexString())"

        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(urlString.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }

        // Scale up for better quality
        let scale = 10.0
        let scaledImage = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else { return nil }

        return UIImage(cgImage: cgImage)
    }
}

private struct ChannelInfoSecretKeySection: View {
    let channel: ChannelDTO
    @Binding var copyHapticTrigger: Int

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(L10n.Chats.Chats.ChannelInfo.secretKey)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                HStack {
                    Text(channel.secret.hexString())
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)

                    Spacer()

                    Button(L10n.Chats.Chats.ChannelInfo.copy, systemImage: "doc.on.doc") {
                        copyHapticTrigger += 1
                        UIPasteboard.general.string = channel.secret.hexString()
                    }
                    .labelStyle(.iconOnly)
                }
            }
        } header: {
            Text(L10n.Chats.Chats.ChannelInfo.manualSharing)
        } footer: {
            Text(L10n.Chats.Chats.ChannelInfo.manualSharingFooter)
        }
    }
}

private struct ChannelInfoActionsSection: View {
    let isActionInProgress: Bool
    let isClearingMessages: Bool
    let isDeleting: Bool
    @Binding var showingClearMessagesConfirmation: Bool
    @Binding var showingDeleteConfirmation: Bool

    var body: some View {
        Section {
            Button {
                showingClearMessagesConfirmation = true
            } label: {
                HStack {
                    Spacer()
                    if isClearingMessages {
                        ProgressView()
                    } else {
                        Label(L10n.Chats.Chats.ChannelInfo.clearMessagesButton, systemImage: "xmark.circle")
                    }
                    Spacer()
                }
            }
            .disabled(isActionInProgress)
            .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }

            Button(role: .destructive) {
                showingDeleteConfirmation = true
            } label: {
                HStack {
                    Spacer()
                    if isDeleting {
                        ProgressView()
                    } else {
                        Label(L10n.Chats.Chats.ChannelInfo.deleteButton, systemImage: "trash")
                    }
                    Spacer()
                }
            }
            .disabled(isActionInProgress)
        } footer: {
            Text(L10n.Chats.Chats.ChannelInfo.deleteFooter)
        }
    }
}

private struct ChannelInfoRegionSection: View {
    let knownRegions: [String]
    let selectedRegionScope: String?
    @Binding var isExpanded: Bool
    @Binding var isDiscovering: Bool
    @Binding var discoveryMessage: String?
    let onRegionSelected: (String?) -> Void
    let onDiscoverTapped: () -> Void
    let onManageTapped: () -> Void

    private var sortedPartitioned: (public: [String], private: [String]) {
        let sorted = knownRegions.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        return (sorted.filter { !$0.isPrivateRegion }, sorted.filter { $0.isPrivateRegion })
    }

    private var regionValueLabel: String {
        if knownRegions.isEmpty {
            return L10n.Chats.Chats.ChannelInfo.Region.notConfigured
        }
        if let scope = selectedRegionScope {
            return scope
        }
        return L10n.Chats.Chats.ChannelInfo.Region.allRegions
    }

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $isExpanded) {
                if knownRegions.isEmpty {
                    ChannelInfoRegionEmptyContent(
                        isDiscovering: isDiscovering,
                        discoveryMessage: discoveryMessage,
                        onDiscoverTapped: onDiscoverTapped,
                        onManageTapped: onManageTapped
                    )
                } else {
                    ChannelInfoRegionPickerContent(
                        selectedRegionScope: selectedRegionScope,
                        publicRegions: sortedPartitioned.public,
                        privateRegions: sortedPartitioned.private,
                        isDiscovering: isDiscovering,
                        discoveryMessage: discoveryMessage,
                        onRegionSelected: onRegionSelected,
                        onDiscoverTapped: onDiscoverTapped,
                        onManageTapped: onManageTapped
                    )
                }
            } label: {
                ChannelInfoRegionLabel(regionValueLabel: regionValueLabel)
            }
        }
    }
}

private struct ChannelInfoRegionLabel: View {
    let regionValueLabel: String

    var body: some View {
        HStack {
            Label(L10n.Chats.Chats.ChannelInfo.region, systemImage: "globe")
            Spacer()
            Text(regionValueLabel)
                .foregroundStyle(.secondary)
        }
    }
}

private struct ChannelInfoRegionActions: View {
    let isDiscovering: Bool
    let discoveryMessage: String?
    let onDiscoverTapped: () -> Void
    let onManageTapped: () -> Void

    var body: some View {
        if isDiscovering {
            HStack {
                ProgressView()
                Text(L10n.Chats.Chats.ChannelInfo.Region.discovering)
                    .foregroundStyle(.secondary)
            }
        } else if let discoveryMessage {
            Text(discoveryMessage)
                .font(.caption)
                .foregroundStyle(.secondary)
        }

        Button(L10n.Chats.Chats.ChannelInfo.Region.discover, systemImage: "antenna.radiowaves.left.and.right") {
            onDiscoverTapped()
        }
        .disabled(isDiscovering)

        Button(L10n.Chats.Chats.ChannelInfo.Region.manageRegions, systemImage: "list.bullet") {
            onManageTapped()
        }
    }
}

private struct ChannelInfoRegionEmptyContent: View {
    let isDiscovering: Bool
    let discoveryMessage: String?
    let onDiscoverTapped: () -> Void
    let onManageTapped: () -> Void

    var body: some View {
        Text(L10n.Chats.Chats.ChannelInfo.Region.explanation)
            .font(.subheadline)
            .foregroundStyle(.secondary)

        ChannelInfoRegionActions(
            isDiscovering: isDiscovering,
            discoveryMessage: discoveryMessage,
            onDiscoverTapped: onDiscoverTapped,
            onManageTapped: onManageTapped
        )
    }
}

private struct ChannelInfoRegionPickerContent: View {
    let selectedRegionScope: String?
    let publicRegions: [String]
    let privateRegions: [String]
    let isDiscovering: Bool
    let discoveryMessage: String?
    let onRegionSelected: (String?) -> Void
    let onDiscoverTapped: () -> Void
    let onManageTapped: () -> Void

    var body: some View {
        // "All Regions" option
        Button {
            onRegionSelected(nil)
        } label: {
            HStack {
                Text(L10n.Chats.Chats.ChannelInfo.Region.allRegions)
                Spacer()
                if selectedRegionScope == nil {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)

        // Public regions
        ForEach(publicRegions, id: \.self) { region in
            Button {
                onRegionSelected(region)
            } label: {
                HStack {
                    Text(region)
                    Spacer()
                    if selectedRegionScope == region {
                        Image(systemName: "checkmark")
                            .foregroundStyle(.tint)
                    }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }

        // Private regions (shown disabled)
        ForEach(privateRegions, id: \.self) { region in
            HStack {
                Text(region)
                Spacer()
                Text(L10n.Chats.Chats.ChannelInfo.Region.`private`)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .foregroundStyle(.secondary)
        }

        ChannelInfoRegionActions(
            isDiscovering: isDiscovering,
            discoveryMessage: discoveryMessage,
            onDiscoverTapped: onDiscoverTapped,
            onManageTapped: onManageTapped
        )
    }
}

#Preview {
    ChannelInfoSheet(
        channel: ChannelDTO(from: Channel(
            radioID: UUID(),
            index: 1,
            name: "General",
            secret: Data(repeating: 0xAB, count: 16)
        )),
        onClearMessages: {},
        onDelete: {}
    )
    .environment(\.appState, AppState())
}
