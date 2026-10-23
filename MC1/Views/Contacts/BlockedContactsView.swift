import SwiftUI

struct BlockedContactsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var ownedState: BlockedContactsState
  private let suppliedState: BlockedContactsState?
  private let rendererRevision: Int

  init() {
    _ownedState = State(initialValue: BlockedContactsState())
    suppliedState = nil
    rendererRevision = 0
  }

  init(state: BlockedContactsState, rendererRevision: Int = 0) {
    _ownedState = State(initialValue: state)
    suppliedState = state
    self.rendererRevision = rendererRevision
  }

  private var state: BlockedContactsState {
    suppliedState ?? ownedState
  }

  var body: some View {
    if suppliedState == nil {
      content.modifier(BlockedContactsPresenter(state: state))
    } else {
      content
    }
  }

  private var content: some View {
    Group {
      if state.isLoading {
        ProgressView(L10n.Contacts.Contacts.Blocked.loading)
      } else if state.contacts.isEmpty {
        ContentUnavailableView(
          L10n.Contacts.Contacts.Blocked.Empty.title,
          systemImage: "hand.raised.slash",
          description: Text(L10n.Contacts.Contacts.Blocked.Empty.description)
        )
      } else {
        blockedList
      }
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Contacts.Contacts.Blocked.title)
  }

  private static let rowSeparatorLeadingInset: CGFloat = 72
  private static let rowHorizontalPadding: CGFloat = 16
  private static let rowVerticalPadding: CGFloat = 6

  private var blockedList: some View {
    ScrollView {
      LazyVStack(spacing: 0) {
        ForEach(Array(state.contacts.enumerated()), id: \.element.id) { index, contact in
          let isSelected = appState.navigation.selectedContact?.id == contact.id
          VStack(spacing: 0) {
            Button {
              appState.navigation.navigateToContactDetail(contact)
            } label: {
              ContactRowView(contact: contact, timestamp: contact.recencyTimestamp)
                .padding(.horizontal, Self.rowHorizontalPadding)
                .padding(.vertical, Self.rowVerticalPadding)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .selectedRowHighlight(isSelected: isSelected)
            .accessibilityAddTraits(isSelected ? .isSelected : [])
            if index < state.contacts.count - 1 {
              Divider().padding(.leading, Self.rowSeparatorLeadingInset)
            }
          }
          .id(contact.id)
          .transition(.opacity)
        }
      }
      .scrollTargetLayout()
    }
    .scrollPosition(id: Binding(
      get: { state.scrollPositionID },
      set: { state.updateScrollPosition($0, rendererRevision: rendererRevision) }
    ), anchor: .top)
  }
}
