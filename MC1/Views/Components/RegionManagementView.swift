import SwiftUI

/// Form-based view for managing known regions with add/delete functionality
struct RegionManagementView: View {
  let knownRegions: [String]
  @Binding var isDiscovering: Bool
  @Binding var discoveryMessage: String?

  let onRemoveRegion: (String) -> Void
  let onAddRegion: (String) -> Void
  let onDiscoverTapped: () -> Void

  @Environment(\.appTheme) private var theme
  @State private var searchText = ""
  @State private var showingAddAlert = false
  @State private var newRegionName = ""
  @State private var validationMessage: String?

  private var filteredRegions: [String] {
    let sorted = knownRegions.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    guard !searchText.isEmpty else { return sorted }
    return sorted.filter { $0.localizedStandardContains(searchText) }
  }

  var body: some View {
    Form {
      if knownRegions.isEmpty {
        RegionManagementEmptyState()
      } else {
        KnownRegionsSection(
          regions: filteredRegions,
          onDelete: removeRegions
        )
      }

      ActionsSection(
        isDiscovering: isDiscovering,
        discoveryMessage: discoveryMessage,
        onDiscoverTapped: onDiscoverTapped,
        onAddTapped: {
          newRegionName = ""
          showingAddAlert = true
        }
      )
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Chats.Chats.ChannelInfo.Region.manage)
    .modifier(SearchableModifier(searchText: $searchText, isEnabled: knownRegions.count >= 15))
    .alert(L10n.Chats.Chats.ChannelInfo.Region.addRegionTitle, isPresented: $showingAddAlert) {
      TextField(L10n.Chats.Chats.ChannelInfo.Region.addRegionPlaceholder, text: $newRegionName)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
      Button(L10n.Chats.Chats.ChannelInfo.Region.addSelected) {
        if let error = RegionNameValidator.validate(newRegionName, existingRegions: knownRegions) {
          validationMessage = validationText(for: error)
          Task { showingAddAlert = true }
          return
        }
        validationMessage = nil
        onAddRegion(newRegionName.trimmingCharacters(in: .whitespaces))
      }
      Button(L10n.Chats.Chats.Common.cancel, role: .cancel) {
        validationMessage = nil
      }
    } message: {
      if let validationMessage {
        Text(validationMessage)
      }
    }
  }

  private func validationText(for error: RegionNameValidator.ValidationError) -> String? {
    switch error {
    case .empty: nil
    case .invalidCharacters: L10n.Chats.Chats.ChannelInfo.Region.invalidName
    case let .tooLong(maxBytes): L10n.Chats.Chats.ChannelInfo.Region.nameTooLong(maxBytes)
    case .duplicate: L10n.Chats.Chats.ChannelInfo.Region.duplicate
    }
  }

  private func removeRegions(at offsets: IndexSet) {
    let regionsToRemove = offsets.map { filteredRegions[$0] }
    for region in regionsToRemove {
      onRemoveRegion(region)
    }
  }
}

// MARK: - Extracted Views

private struct RegionManagementEmptyState: View {
  var body: some View {
    ContentUnavailableView {
      Label(L10n.Chats.Chats.ChannelInfo.Region.noRegions, systemImage: "map")
    } description: {
      Text(L10n.Chats.Chats.ChannelInfo.Region.noRegionsDescription)
    }
  }
}

private struct KnownRegionsSection: View {
  @Environment(\.appTheme) private var theme
  let regions: [String]
  let onDelete: (IndexSet) -> Void

  var body: some View {
    Section {
      ForEach(regions, id: \.self) { region in
        RegionRow(name: region)
      }
      .onDelete(perform: onDelete)
    }
    .themedRowBackground(theme)
  }
}

private struct RegionRow: View {
  let name: String

  private var isPrivate: Bool {
    name.isPrivateRegion
  }

  var body: some View {
    HStack {
      Text(name)
      if isPrivate {
        Spacer()
        Text(L10n.Chats.Chats.ChannelInfo.Region.private)
          .font(.caption)
          .foregroundStyle(.tertiary)
      }
    }
  }
}

private struct ActionsSection: View {
  @Environment(\.appTheme) private var theme
  let isDiscovering: Bool
  let discoveryMessage: String?
  let onDiscoverTapped: () -> Void
  let onAddTapped: () -> Void

  var body: some View {
    Section {
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

      Button(L10n.Chats.Chats.ChannelInfo.Region.addManually, systemImage: "plus") {
        onAddTapped()
      }
    } footer: {
      Text(L10n.Chats.Chats.ChannelInfo.Region.invalidName)
    }
    .themedRowBackground(theme)
  }
}

/// Conditionally applies `.searchable()` when the region count warrants it
private struct SearchableModifier: ViewModifier {
  @Binding var searchText: String
  let isEnabled: Bool

  func body(content: Content) -> some View {
    if isEnabled {
      content.searchable(text: $searchText)
    } else {
      content
    }
  }
}
