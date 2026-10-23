import SwiftUI

struct ContactDetailContent: View {
  @Environment(\.appTheme) private var theme
  @Bindable var state: ContactDetailState
  var historyRoute: ((ContactRoute.TelemetryHistory) -> NodesRoute)?
  private let rendererRevision: Int
  @State private var headerHeight: CGFloat = 150
  @State private var rendererID = UUID()
  @State private var restoredScroll = false
  @State private var restorationTarget: ContactDetailState.ScrollSection?
  @State private var rowPositions: [ContactDetailState.ScrollSection: ContactDetailRowPosition.Measurement] = [:]
  @State private var viewport = CGRect.zero

  init(
    state: ContactDetailState,
    historyRoute: ((ContactRoute.TelemetryHistory) -> NodesRoute)? = nil,
    rendererRevision: Int = 0
  ) {
    self.state = state
    self.historyRoute = historyRoute
    self.rendererRevision = rendererRevision
  }

  var body: some View {
    ScrollViewReader { proxy in
      List {
        ContactProfileSection(
          currentContact: state.currentContact,
          contactTypeLabel: state.contactTypeLabel,
          measuredHeight: $headerHeight,
          isSavingAvatar: state.isSavingAvatar,
          showAvatarSourceMenu: $state.showAvatarSourceMenu,
          onRowPosition: recordRowPosition
        )

        ContactActionsSection(
          currentContact: state.currentContact,
          isFavorite: $state.isFavorite,
          isSettingFavorite: state.isSettingFavorite,
          showFromDirectChat: state.showFromDirectChat,
          isPinging: state.isPinging,
          pingResult: state.pingResult,
          onJoinRoom: { state.showRoomJoinSheet = true },
          onShowTelemetry: { state.showTelemetry() },
          onShowRemoteCLI: { state.showRemoteCLI() },
          onShowAdminAccess: { state.showAdminAccess() },
          onPingRepeater: { state.perform(.ping) },
          onShareQR: { state.showQRShareSheet = true },
          onShareViaAdvert: { state.perform(.share) },
          isSharing: state.isSharing,
          showShareSuccess: state.showShareSuccess,
          historyRoute: historyRoute,
          onRowPosition: recordRowPosition
        )
        .themedRowBackground(theme)

        ContactDetailInfoSection(
          state: state, rendererID: rendererID, rendererRevision: rendererRevision,
          onRowPosition: recordRowPosition
        )
        .themedRowBackground(theme)

        if state.currentContact.hasLocation {
          ContactLocationSection(
            currentContact: state.currentContact,
            showFullMap: $state.showFullMap,
            onRowPosition: recordRowPosition
          )
          .themedRowBackground(theme)
        }

        ContactNetworkPathSection(state: state, onRowPosition: recordRowPosition)
          .themedRowBackground(theme)

        ContactTechnicalSection(
          currentContact: state.currentContact,
          contactTypeLabel: state.contactTypeLabel,
          onRowPosition: recordRowPosition
        )
        .themedRowBackground(theme)

        ContactDangerSection(
          currentContact: state.currentContact,
          contactTypeLabel: state.contactTypeLabel,
          isClearingMessages: state.isClearingMessages,
          isVContact: state.isVContact,
          onClearMessages: { state.showingClearMessagesAlert = true },
          onToggleBlock: {
            if state.currentContact.isBlocked {
              state.perform(.block)
            } else {
              state.showingBlockAlert = true
            }
          },
          onDelete: { state.showingDeleteAlert = true },
          onRowPosition: recordRowPosition
        )
        .themedRowBackground(theme)
      }
      .themedCanvas(theme)
      .navigationTitle(state.currentContact.displayName)
      .navigationBarTitleDisplayMode(.inline)
      .scrollRevealNavigationTitle(state.currentContact.displayName, revealAfter: headerHeight)
      .contentMargins(.top, 0, for: .scrollContent)
      .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
        viewport = frame
      }
      .onChange(of: isCurrentRenderer ? viewport : nil, initial: true) { _, frame in
        guard let frame, !frame.isEmpty, !restoredScroll else { return }
        if let saved = state.scrollSection {
          let target = restoredSection(saved)
          restorationTarget = target
          proxy.scrollTo(target, anchor: .top)
        } else {
          restoredScroll = true
        }
      }
      .onChange(of: rowPositions) { _, _ in updateVisibleSection() }
      .onChange(of: viewport) { _, _ in updateVisibleSection() }
      .onAppear {
        state.mountRenderer(rendererID, revision: rendererRevision)
      }
      .onDisappear {
        state.unmountRenderer(rendererID, revision: rendererRevision)
        restoredScroll = false
      }
    }
    .accessibilityIdentifier("contact-detail-\(state.currentContact.id)")
    .preference(
      key: ContactDetailIdentityPreference.self,
      value: isCurrentRenderer ? state.currentContact.id : nil
    )
  }

  private var isCurrentRenderer: Bool {
    state.rendererRevision == rendererRevision && state.rendererID == rendererID && state.hasPresentationOwnership
  }

  private func restoredSection(_ saved: ContactDetailState.ScrollSection) -> ContactDetailState.ScrollSection {
    if saved == .location, !state.currentContact.hasLocation { return .outPath }
    if saved == .danger, state.isVContact, state.currentContact.type != .chat { return .technical }
    return saved
  }

  private var visibleSection: ContactDetailState.ScrollSection? {
    guard !viewport.isEmpty else { return nil }
    return ContactDetailState.ScrollSection.allCases.first {
      rowPositions[$0]?.frame?.intersects(viewport) == true
    }
  }

  private func recordRowPosition(_ measurement: ContactDetailRowPosition.Measurement) {
    if measurement.frame != nil {
      rowPositions[measurement.section] = measurement
    } else if rowPositions[measurement.section]?.id == measurement.id {
      rowPositions[measurement.section] = nil
    }
  }

  private func updateVisibleSection() {
    guard isCurrentRenderer else { return }
    guard let section = visibleSection else { return }
    if !restoredScroll {
      guard section == restorationTarget else { return }
      restoredScroll = true
    }
    state.scrollSection = section
  }
}
