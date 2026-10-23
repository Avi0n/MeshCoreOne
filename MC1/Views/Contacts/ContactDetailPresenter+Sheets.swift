import MC1Services
import SwiftUI

extension ContactDetailPresenter {
  @ViewBuilder
  var roomJoinSheet: some View {
    if let state, state.hasPresentationOwnership, let role = RemoteNodeRole(contactType: state.currentContact.type) {
      let context = state.operationContext
      NodeAuthenticationSheet(contact: state.currentContact, role: role) { session in
        guard state.accepts(context) else { return }
        state.appState?.navigation.navigateToRoom(with: session)
      }
      .presentationSizing(.page)
    }
  }

  @ViewBuilder
  var adminAuthenticationSheet: some View {
    if let state, state.hasPresentationOwnership, let role = RemoteNodeRole(contactType: state.currentContact.type) {
      let context = state.operationContext
      NodeAuthenticationSheet(contact: state.currentContact, role: role) { session in
        guard state.accepts(context) else { return }
        state.adminSession = session
        state.adminSessionContext = context
        state.showRepeaterAdminAuth = false
      }
      .presentationSizing(.page)
    }
  }

  @ViewBuilder
  func activeSheet(_ active: ContactDetailState.ActiveSheet) -> some View {
    if let state, state.hasPresentationOwnership {
      switch active {
      case .nodeAuth:
        if let role = RemoteNodeRole(contactType: state.currentContact.type) {
          let context = state.operationContext
          NodeAuthenticationSheet(
            contact: state.currentContact,
            role: role,
            customTitle: L10n.Contacts.Contacts.Detail.telemetryAccess
          ) { session in
            guard state.accepts(context) else { return }
            state.pendingSheet = state.currentContact.type == .room ? .roomStatus(session) : .repeaterStatus(session)
            state.pendingSheetContext = context
            state.activeSheet = nil
          }
          .presentationSizing(.page)
        }
      case let .repeaterStatus(session):
        RepeaterStatusView(session: session)
      case let .roomStatus(session):
        RoomStatusView(session: session)
      case let .nodeTelemetry(contact):
        NodeTelemetryView(contact: contact)
      case let .remoteCLI(contact):
        NavigationStack {
          RemoteCLISheet(contact: contact)
            .toolbar {
              ToolbarItem(placement: .confirmationAction) {
                Button(L10n.RemoteNodes.RemoteNodes.done) {
                  guard state.hasPresentationOwnership else { return }
                  state.activeSheet = nil
                }
              }
            }
        }
        .presentationSizing(.page)
      case let .adminSettings(session):
        NavigationStack {
          Group {
            if session.isRoom {
              RoomSettingsView(session: session)
            } else {
              RepeaterSettingsView(session: session)
            }
          }
          .toolbar {
            ToolbarItem(placement: .confirmationAction) {
              Button(L10n.RemoteNodes.RemoteNodes.done) {
                guard state.hasPresentationOwnership else { return }
                state.activeSheet = nil
              }
            }
          }
        }
        .presentationSizing(.page)
      }
    }
  }
}
