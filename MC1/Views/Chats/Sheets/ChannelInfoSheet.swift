import CoreImage.CIFilterBuiltins
import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "ChannelInfoSheet")

/// Sheet displaying channel info with sharing and deletion options
struct ChannelInfoSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.chatViewModel) private var viewModel
  @Environment(\.appTheme) private var theme

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
  @State private var isRegionExpanded = false
  @State private var isDiscoveringRegions = false
  @State private var discoveryMessage: String?
  @State private var showingRegionManagement = false
  @State private var discoveryTask: Task<Void, Never>?
  @State private var discoveredNewRegions: [String] = []
  @State private var showingDiscoveryResults = false
  @State private var selectedFloodScope: ChannelFloodScope
  @State private var headerHeight: CGFloat = 150

  init(channel: ChannelDTO, onClearMessages: @escaping () -> Void, onDelete: @escaping () -> Void) {
    self.channel = channel
    self.onClearMessages = onClearMessages
    self.onDelete = onDelete
    _notificationLevel = State(initialValue: channel.notificationLevel)
    _isFavorite = State(initialValue: channel.isFavorite)
    _selectedFloodScope = State(initialValue: channel.floodScope)
  }

  var body: some View {
    NavigationStack {
      Form {
        // Channel Header Section
        ChannelInfoHeaderSection(channel: channel, measuredHeight: $headerHeight)

        // Quick Actions Section
        ConversationQuickActionsSection(
          isFavorite: $isFavorite,
          notificationLevel: $notificationLevel,
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
          selectedFloodScope: selectedFloodScope,
          deviceDefaultFloodScopeName: appState.connectedDevice?.defaultFloodScopeName,
          isExpanded: $isRegionExpanded,
          onFloodScopeSelected: { scope in
            Task { await selectFloodScope(scope) }
          },
          onManageTapped: {
            showingRegionManagement = true
          }
        )

        // QR Code Section (only for private channels with secrets)
        if channel.hasSecret, !channel.isPublicChannel {
          ChannelInfoQRCodeSection(channel: channel)
        }

        // Secret Key Section (only for private channels)
        if channel.hasSecret, !channel.isPublicChannel {
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
          .themedRowBackground(theme)
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
      .themedCanvas(theme)
      .navigationBarTitleDisplayMode(.inline)
      .scrollRevealNavigationTitle(channel.displayName, revealAfter: headerHeight)
      .contentMargins(.top, 0, for: .scrollContent)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Chats.Chats.Common.done) {
            dismiss()
          }
        }
      }
      .navigationDestination(isPresented: $showingRegionManagement) {
        RegionManagementView(
          knownRegions: knownRegions,
          isDiscovering: $isDiscoveringRegions,
          discoveryMessage: $discoveryMessage,
          onRemoveRegion: { region in
            appState.connectionManager.removeKnownRegion(region)
          },
          onAddRegion: { region in
            appState.connectionManager.addKnownRegion(region)
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
          for region in selected {
            appState.connectionManager.addKnownRegion(region)
          }
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
      errorMessage = error.userFacingMessage
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
      errorMessage = error.userFacingMessage
      isClearingMessages = false
    }
  }

  private func selectFloodScope(_ scope: ChannelFloodScope) async {
    let previous = selectedFloodScope
    selectedFloodScope = scope
    do {
      try await appState.offlineDataStore?.setChannelFloodScope(channel.id, floodScope: scope)
    } catch {
      logger.error("Failed to save flood scope: \(error.localizedDescription)")
      selectedFloodScope = previous
      return
    }

    if let session = appState.services?.session {
      let resolved = ChannelFloodScopeResolver.resolve(
        channelFloodScope: scope,
        deviceDefaultFloodScopeName: appState.connectedDevice?.defaultFloodScopeName,
        supportsUnscopedFloodSend: appState.connectedDevice?.supportsUnscopedFloodSend ?? false
      )
      switch resolved {
      case .unscoped:
        try? await session.setFloodScopeUnscoped()
      case let .scope(scope):
        try? await session.setFloodScope(scope)
      }
    }
  }

  private var knownRegions: [String] {
    appState.connectedDevice?.knownRegions ?? []
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

    let outcome = await RegionDiscoveryService.discover(
      session: session,
      contactService: contactService,
      dataStore: appState.offlineDataStore,
      radioID: channel.radioID,
      knownRegions: knownRegions,
      supportsAdHocRequest: appState.connectedDevice?.supportsAdHocRepeaterRequest ?? false
    )

    switch outcome {
    case .sendFailed:
      return []
    case .noRepeatersResponded:
      discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded
      return []
    case .errorLoadingRepeaters:
      discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errLoadingRepeaters
      return []
    case let .completed(newRegions, allRepeatersTableFull):
      if newRegions.isEmpty, allRepeatersTableFull {
        discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errRadioContactsFull
      }
      return newRegions
    }
  }
}

// MARK: - Extracted Views

private struct ChannelInfoHeaderSection: View {
  let channel: ChannelDTO
  @Binding var measuredHeight: CGFloat

  private var channelTypeLabel: String {
    if channel.isPublicChannel {
      L10n.Chats.Chats.ChannelInfo.ChannelType.public
    } else if channel.name.hasPrefix("#") {
      L10n.Chats.Chats.ChannelInfo.ChannelType.hashtag
    } else {
      L10n.Chats.Chats.ChannelInfo.ChannelType.private
    }
  }

  var body: some View {
    Section {
      VStack(spacing: 12) {
        ChannelAvatar(channel: channel, size: 150)

        VStack(spacing: 4) {
          Text(channel.displayName)
            .font(.title2)
            .bold()

          Text(channelTypeLabel)
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
      }
      .frame(maxWidth: .infinity)
      .scrollRevealHeaderHeight($measuredHeight)
      .listRowBackground(Color.clear)
    }
  }
}

private struct ChannelInfoQRCodeSection: View {
  @Environment(\.appTheme) private var theme
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
    .themedRowBackground(theme)
    .task {
      qrImage = generateQRCode()
    }
  }

  private func generateQRCode() -> UIImage? {
    // Format: meshcore://channel/add?name=<name>&secret=<hex>
    let urlString = "meshcore://channel/add?name=\(channel.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")&secret=\(channel.secret.uppercaseHexString())"

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
  @Environment(\.appTheme) private var theme
  let channel: ChannelDTO
  @Binding var copyHapticTrigger: Int

  var body: some View {
    Section {
      VStack(alignment: .leading, spacing: 8) {
        Text(L10n.Chats.Chats.ChannelInfo.secretKey)
          .font(.caption)
          .foregroundStyle(.secondary)

        HStack {
          Text(channel.secret.uppercaseHexString())
            .font(.system(.body, design: .monospaced))
            .textSelection(.enabled)

          Spacer()

          Button(L10n.Chats.Chats.ChannelInfo.copy, systemImage: "doc.on.doc") {
            copyHapticTrigger += 1
            UIPasteboard.general.string = channel.secret.uppercaseHexString()
          }
          .labelStyle(.iconOnly)
        }
      }
    } header: {
      Text(L10n.Chats.Chats.ChannelInfo.manualSharing)
    } footer: {
      Text(L10n.Chats.Chats.ChannelInfo.manualSharingFooter)
    }
    .themedRowBackground(theme)
  }
}

private struct ChannelInfoActionsSection: View {
  @Environment(\.appTheme) private var theme
  let isActionInProgress: Bool
  let isClearingMessages: Bool
  let isDeleting: Bool
  @Binding var showingClearMessagesConfirmation: Bool
  @Binding var showingDeleteConfirmation: Bool

  var body: some View {
    Section {
      Button(role: .destructive) {
        showingClearMessagesConfirmation = true
      } label: {
        HStack {
          Label(L10n.Chats.Chats.ChannelInfo.clearMessagesButton, systemImage: "xmark.circle")
          if isClearingMessages {
            Spacer()
            ProgressView()
          }
        }
      }
      .disabled(isActionInProgress)

      Button(role: .destructive) {
        showingDeleteConfirmation = true
      } label: {
        HStack {
          Label(L10n.Chats.Chats.ChannelInfo.deleteButton, systemImage: "trash")
          if isDeleting {
            Spacer()
            ProgressView()
          }
        }
      }
      .disabled(isActionInProgress)
    } footer: {
      Text(L10n.Chats.Chats.ChannelInfo.deleteFooter)
    }
    .themedRowBackground(theme)
  }
}

private struct ChannelInfoRegionSection: View {
  @Environment(\.appTheme) private var theme
  let knownRegions: [String]
  let selectedFloodScope: ChannelFloodScope
  let deviceDefaultFloodScopeName: String?
  @Binding var isExpanded: Bool
  let onFloodScopeSelected: (ChannelFloodScope) -> Void
  let onManageTapped: () -> Void

  private var sortedPartitioned: (public: [String], private: [String]) {
    let sorted = knownRegions.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    return (sorted.filter { !$0.isPrivateRegion }, sorted.filter(\.isPrivateRegion))
  }

  private var regionValueLabel: String {
    if knownRegions.isEmpty {
      return L10n.Chats.Chats.ChannelInfo.Region.notConfigured
    }
    switch selectedFloodScope {
    case .inherit:
      if let name = deviceDefaultFloodScopeName, !name.isEmpty {
        return L10n.Chats.Chats.ChannelInfo.Region.useDefaultFormat(name)
      }
      return L10n.Chats.Chats.ChannelInfo.Region.allRegions
    case .allRegions:
      return L10n.Chats.Chats.ChannelInfo.Region.allRegions
    case let .region(name):
      return name
    }
  }

  var body: some View {
    Section {
      DisclosureGroup(isExpanded: $isExpanded) {
        if knownRegions.isEmpty {
          ChannelInfoRegionEmptyContent(onManageTapped: onManageTapped)
        } else {
          ChannelInfoRegionPickerContent(
            selectedFloodScope: selectedFloodScope,
            deviceDefaultFloodScopeName: deviceDefaultFloodScopeName,
            publicRegions: sortedPartitioned.public,
            privateRegions: sortedPartitioned.private,
            onFloodScopeSelected: onFloodScopeSelected,
            onManageTapped: onManageTapped
          )
        }
      } label: {
        ChannelInfoRegionLabel(regionValueLabel: regionValueLabel)
      }
    }
    .themedRowBackground(theme)
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
  let onManageTapped: () -> Void

  var body: some View {
    Button(L10n.Chats.Chats.ChannelInfo.Region.manageRegions, systemImage: "list.bullet") {
      onManageTapped()
    }
  }
}

private struct ChannelInfoRegionEmptyContent: View {
  let onManageTapped: () -> Void

  var body: some View {
    Text(L10n.Chats.Chats.ChannelInfo.Region.explanation)
      .font(.subheadline)
      .foregroundStyle(.secondary)

    ChannelInfoRegionActions(onManageTapped: onManageTapped)
  }
}

private struct ChannelInfoRegionPickerContent: View {
  let selectedFloodScope: ChannelFloodScope
  let deviceDefaultFloodScopeName: String?
  let publicRegions: [String]
  let privateRegions: [String]
  let onFloodScopeSelected: (ChannelFloodScope) -> Void
  let onManageTapped: () -> Void

  private var effectiveDefault: String? {
    guard let name = deviceDefaultFloodScopeName, !name.isEmpty else { return nil }
    return name
  }

  var body: some View {
    if let defaultName = effectiveDefault {
      pickerRow(
        title: L10n.Chats.Chats.ChannelInfo.Region.useDefaultFormat(defaultName),
        isSelected: selectedFloodScope == .inherit,
        scope: .inherit
      )
    }

    pickerRow(
      title: L10n.Chats.Chats.ChannelInfo.Region.allRegions,
      isSelected: selectedFloodScope == allRegionsSelection,
      scope: allRegionsSelection
    )

    ForEach(publicRegions, id: \.self) { region in
      pickerRow(
        title: region,
        isSelected: selectedFloodScope == .region(region),
        scope: .region(region)
      )
    }

    // Private regions (shown disabled — can't scope to these)
    ForEach(privateRegions, id: \.self) { region in
      HStack {
        Text(region)
        Spacer()
        Text(L10n.Chats.Chats.ChannelInfo.Region.private)
          .font(.caption)
          .foregroundStyle(.tertiary)
      }
      .foregroundStyle(.secondary)
    }

    ChannelInfoRegionActions(onManageTapped: onManageTapped)
  }

  /// When no device default is set, `.inherit` and `.allRegions` collapse into a
  /// single row — writing `.inherit` is the conservative choice so a later default
  /// change flows through naturally.
  private var allRegionsSelection: ChannelFloodScope {
    effectiveDefault != nil ? .allRegions : .inherit
  }

  private func pickerRow(title: String, isSelected: Bool, scope: ChannelFloodScope) -> some View {
    Button {
      onFloodScopeSelected(scope)
    } label: {
      HStack {
        Text(title)
        Spacer()
        if isSelected {
          Image(systemName: "checkmark")
            .foregroundStyle(.tint)
        }
      }
      .contentShape(.rect)
    }
    .buttonStyle(.plain)
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
