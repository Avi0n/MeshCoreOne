import MC1Services
import SwiftUI

// MARK: - Contact Detail Sheet

struct ContactDetailSheet: View {
  let contact: ContactDTO
  let onMessage: () -> Void
  let onDelete: () -> Void
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState

  init(contact: ContactDTO, onMessage: @escaping () -> Void, onDelete: @escaping () -> Void) {
    self.contact = contact
    self.onMessage = onMessage
    self.onDelete = onDelete
    _isFavorite = State(initialValue: contact.isFavorite)
  }

  /// Sheet types for repeater flows
  private enum ActiveSheet: Identifiable, Hashable {
    case telemetryAuth
    case telemetryStatus(RemoteNodeSessionDTO)
    case adminAuth
    case adminSettings(RemoteNodeSessionDTO)
    case roomJoin

    var id: String {
      switch self {
      case .telemetryAuth: "telemetryAuth"
      case let .telemetryStatus(s): "telemetryStatus-\(s.id)"
      case .adminAuth: "adminAuth"
      case let .adminSettings(s): "adminSettings-\(s.id)"
      case .roomJoin: "roomJoin"
      }
    }
  }

  @State private var activeSheet: ActiveSheet?
  @State private var pendingSheet: ActiveSheet?
  @State private var isPinging = false
  @State private var pingResult: PingResult?
  @State private var showingDeleteAlert = false
  @State private var isDeleting = false
  @State private var isFavorite: Bool
  @State private var isTogglingFavorite = false
  @State private var errorMessage: String?

  /// ZephCore V-contact remove is disabled (would turn off firmware admin CLI).
  private var isVContact: Bool {
    guard let selfKey = appState.connectedDevice?.publicKey else { return false }
    return VContactIdentity.isVContact(publicKey: contact.publicKey, selfPublicKey: selfKey)
  }

  var body: some View {
    NavigationStack {
      List {
        // Basic info section
        Section(L10n.Map.Map.Detail.Section.contactInfo) {
          LabeledContent(L10n.Map.Map.Detail.name, value: contact.displayName)

          LabeledContent(L10n.Map.Map.Detail.type) {
            HStack {
              Image(systemName: contact.type.iconSystemName)
              Text(typeDisplayName)
            }
            .foregroundStyle(contact.type.displayColor)
          }

          if isFavorite {
            LabeledContent(L10n.Map.Map.Detail.status) {
              HStack {
                Image(systemName: "star.fill")
                Text(L10n.Map.Map.Detail.favorite)
              }
              .foregroundStyle(.orange)
            }
          }

          if contact.lastAdvertTimestamp > 0 {
            LabeledContent(L10n.Map.Map.Detail.lastAdvert) {
              ConversationTimestamp(date: Date(timeIntervalSince1970: TimeInterval(contact.lastAdvertTimestamp)), font: .body)
            }
          }

          VStack(alignment: .leading, spacing: 4) {
            Text(L10n.Map.Map.Detail.publicKey)
              .font(.caption)
              .foregroundStyle(.secondary)
            Text(contact.publicKey.uppercaseHexString(separator: " "))
              .font(.system(.caption, design: .monospaced))
              .textSelection(.enabled)
          }
        }

        // Location section
        Section(L10n.Map.Map.Detail.Section.location) {
          LabeledContent(L10n.Map.Map.Detail.latitude) {
            Text(contact.latitude, format: .number.precision(.fractionLength(6)))
          }

          LabeledContent(L10n.Map.Map.Detail.longitude) {
            Text(contact.longitude, format: .number.precision(.fractionLength(6)))
          }
        }

        // Path info section
        Section(L10n.Map.Map.Detail.Section.outboundPath) {
          if contact.isFloodRouted {
            LabeledContent(L10n.Map.Map.Detail.routing, value: L10n.Map.Map.Detail.routingFlood)
          } else {
            let hopCount = contact.pathHopCount
            LabeledContent(L10n.Map.Map.Detail.pathLength, value: hopCount == 1 ? L10n.Map.Map.Detail.hopSingular : L10n.Map.Map.Detail.hops(hopCount))
          }
        }

        // Actions section
        Section {
          switch contact.type {
          case .repeater:
            Button {
              activeSheet = .telemetryAuth
            } label: {
              Label(L10n.Map.Map.Detail.Action.telemetry, systemImage: "chart.line.uptrend.xyaxis")
            }
            .radioDisabled(for: appState.connectionState)

            Button {
              activeSheet = .adminAuth
            } label: {
              Label(L10n.Map.Map.Detail.Action.management, systemImage: "gearshape.2")
            }
            .radioDisabled(for: appState.connectionState)

            pingButton

          case .room:
            Button {
              activeSheet = .roomJoin
            } label: {
              Label(L10n.Map.Map.Detail.Action.joinRoom, systemImage: "door.left.hand.open")
            }
            .radioDisabled(for: appState.connectionState)

            pingButton

          case .chat:
            Button {
              dismiss()
              onMessage()
            } label: {
              Label(L10n.Map.Map.Detail.Action.sendMessage, systemImage: "message.fill")
            }
            .radioDisabled(for: appState.connectionState)
          }
        }

        // Favorite section
        Section {
          Button {
            Task { await toggleFavorite() }
          } label: {
            HStack {
              Label(
                isFavorite ? L10n.Contacts.Contacts.Detail.removeFromFavorites : L10n.Contacts.Contacts.Detail.addToFavorites,
                systemImage: isFavorite ? "star.slash" : "star"
              )
              if isTogglingFavorite {
                Spacer()
                ProgressView()
              }
            }
          }
          .disabled(isTogglingFavorite)
          .radioDisabled(for: appState.connectionState)
        }

        // Delete section, hidden for the ZephCore V-contact whose remove is disabled.
        if !isVContact {
          Section {
            Button(role: .destructive) {
              showingDeleteAlert = true
            } label: {
              HStack {
                Label(L10n.Contacts.Contacts.Common.delete, systemImage: "trash")
                if isDeleting {
                  Spacer()
                  ProgressView()
                }
              }
            }
            .disabled(isDeleting)
            .radioDisabled(for: appState.connectionState)
          }
        }
      }
      .navigationTitle(contact.displayName)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Map.Map.Common.done) {
            dismiss()
          }
        }
      }
      .sheet(item: $activeSheet, onDismiss: presentPendingSheet) { sheet in
        switch sheet {
        case .telemetryAuth:
          if let role = RemoteNodeRole(contactType: contact.type) {
            NodeAuthenticationSheet(
              contact: contact,
              role: role,
              customTitle: L10n.Map.Map.Detail.Action.telemetryAccessTitle
            ) { session in
              pendingSheet = .telemetryStatus(session)
              activeSheet = nil
            }
            .presentationSizing(.page)
          }

        case let .telemetryStatus(session):
          RepeaterStatusView(session: session)

        case .adminAuth:
          if let role = RemoteNodeRole(contactType: contact.type) {
            NodeAuthenticationSheet(contact: contact, role: role) { session in
              pendingSheet = .adminSettings(session)
              activeSheet = nil
            }
            .presentationSizing(.page)
          }

        case let .adminSettings(session):
          NavigationStack {
            RepeaterSettingsView(session: session)
              .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                  Button(L10n.Map.Map.Common.done) {
                    activeSheet = nil
                  }
                }
              }
          }
          .presentationSizing(.page)

        case .roomJoin:
          if let role = RemoteNodeRole(contactType: contact.type) {
            NodeAuthenticationSheet(contact: contact, role: role) { session in
              activeSheet = nil
              dismiss()
              appState.navigation.navigateToRoom(with: session)
            }
            .presentationSizing(.page)
          }
        }
      }
      .alert(
        L10n.Contacts.Contacts.Detail.Alert.Delete.title(typeDisplayName),
        isPresented: $showingDeleteAlert
      ) {
        Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
        Button(L10n.Contacts.Contacts.Common.delete, role: .destructive) {
          Task { await deleteContact() }
        }
      } message: {
        Text(L10n.Contacts.Contacts.Detail.Alert.Delete.message(contact.displayName))
      }
      .errorAlert($errorMessage)
    }
  }

  // MARK: - Favorite

  private func toggleFavorite() async {
    isTogglingFavorite = true
    defer { isTogglingFavorite = false }
    let newValue = !isFavorite
    do {
      try await appState.services?.contactService.setContactFavorite(contact.id, isFavorite: newValue)
      isFavorite = newValue
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Delete

  private func deleteContact() async {
    guard let contactService = appState.services?.contactService else { return }
    isDeleting = true
    defer { isDeleting = false }
    do {
      try await contactService.removeContact(radioID: contact.radioID, publicKey: contact.publicKey)
      dismiss()
      onDelete()
    } catch ContactServiceError.contactNotFound {
      do {
        try await contactService.removeLocalContact(contactID: contact.id, publicKey: contact.publicKey)
        dismiss()
        onDelete()
      } catch {
        errorMessage = error.userFacingMessage
      }
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Ping

  @ViewBuilder
  private var pingButton: some View {
    Button {
      Task { await pingContact() }
    } label: {
      HStack {
        Label(L10n.Contacts.Contacts.Detail.ping, systemImage: "wave.3.right")
        if isPinging {
          Spacer()
          ProgressView()
        }
      }
    }
    .disabled(isPinging)
    .radioDisabled(for: appState.connectionState)

    if let result = pingResult {
      PingResultRow(result: result)
    }
  }

  private func pingContact() async {
    guard !isPinging else { return }
    isPinging = true
    pingResult = nil
    pingResult = await PingHelper.zeroHopPing(contact: contact, appState: appState)
    isPinging = false
  }

  // MARK: - Sheet Management

  private func presentPendingSheet() {
    if let next = pendingSheet {
      pendingSheet = nil
      activeSheet = next
    }
  }

  // MARK: - Computed Properties

  private var typeDisplayName: String {
    switch contact.type {
    case .chat:
      L10n.Map.Map.NodeKind.chatContact
    case .repeater:
      L10n.Map.Map.NodeKind.repeater
    case .room:
      L10n.Map.Map.NodeKind.room
    }
  }
}
