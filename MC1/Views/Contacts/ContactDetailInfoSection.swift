import SwiftUI

struct ContactDetailInfoSection: View {
  @Bindable var state: ContactDetailState
  let rendererID: UUID
  let rendererRevision: Int
  let onRowPosition: (ContactDetailRowPosition.Measurement) -> Void
  @FocusState private var nicknameFocused: Bool
  @State private var renderedNicknameSelection: TextSelection?
  @State private var pendingNicknameSelection: TextSelection?

  init(
    state: ContactDetailState,
    rendererID: UUID,
    rendererRevision: Int,
    onRowPosition: @escaping (ContactDetailRowPosition.Measurement) -> Void
  ) {
    self.state = state
    self.rendererID = rendererID
    self.rendererRevision = rendererRevision
    self.onRowPosition = onRowPosition
    _pendingNicknameSelection = State(
      initialValue: state.nicknameFocused && state.isEditingNickname ? state.nicknameSelection : nil
    )
  }

  var body: some View {
    Section {
      HStack {
        Text(L10n.Contacts.Contacts.Detail.nickname)

        Spacer()

        if state.isEditingNickname {
          TextField(L10n.Contacts.Contacts.Detail.nickname, text: nicknameText, selection: nicknameSelection)
            .focused($nicknameFocused)
            .textFieldStyle(.roundedBorder)
            .frame(width: 150)
            .onSubmit { saveNickname() }

          Button(L10n.Contacts.Contacts.Common.save, action: saveNickname)
            .disabled(state.isSaving)
        } else {
          Text(state.currentContact.nickname ?? L10n.Contacts.Contacts.Detail.nicknameNone)
            .foregroundStyle(.secondary)

          Button(action: {
            guard isCurrentRenderer else { return }
            state.isEditingNickname = true
            state.nicknameFocused = true
          }) {
            Image(systemName: "pencil")
          }
          .buttonStyle(.borderless)
          .accessibilityLabel(L10n.Contacts.Contacts.Common.edit)
        }
      }
      .modifier(ContactDetailRowPosition(section: .info, onPosition: onRowPosition))
      .task(id: state.rendererID) {
        guard isCurrentRenderer else { return }
        pendingNicknameSelection = state.nicknameFocused && state.isEditingNickname ? state.nicknameSelection : nil
        let wasFocused = nicknameFocused
        nicknameFocused = state.nicknameFocused && state.isEditingNickname
        if wasFocused, nicknameFocused { restoreNicknameSelection() }
      }
      .onChange(of: state.nicknameFocused) { _, focused in
        guard isCurrentRenderer else { return }
        nicknameFocused = focused && state.isEditingNickname
      }
      .onChange(of: nicknameFocused) { _, focused in
        guard isCurrentRenderer else { return }
        state.nicknameFocused = focused
        if focused {
          restoreNicknameSelection()
          if let selection = renderedNicknameSelection, state.nicknameSelection != selection {
            state.nicknameSelection = selection
          }
        }
      }
      .onChange(of: state.nicknameSelection) { _, selection in
        guard isCurrentRenderer else { return }
        if pendingNicknameSelection != nil {
          pendingNicknameSelection = selection
          if nicknameFocused { restoreNicknameSelection() }
        } else {
          renderedNicknameSelection = selection
        }
      }

      HStack {
        Text(L10n.Contacts.Contacts.Detail.name)
        Spacer()
        Text(state.currentContact.name)
          .foregroundStyle(.secondary)
      }

      // Phone-clock last heard (on-air)
      if let lastHeard = state.currentContact.lastHeardTimestamp, lastHeard > 0 {
        HStack {
          Text(L10n.Contacts.Contacts.Detail.lastHeard)
          Spacer()
          ConversationTimestamp(date: Date(timeIntervalSince1970: TimeInterval(lastHeard)), font: .body)
        }
      }

      // Radio-sourced last advert
      if state.currentContact.lastAdvertTimestamp > 0 {
        HStack {
          Text(L10n.Contacts.Contacts.Detail.lastAdvert)
          Spacer()
          ConversationTimestamp(date: Date(timeIntervalSince1970: TimeInterval(state.currentContact.lastAdvertTimestamp)), font: .body)
        }
      }

      if state.currentContact.unreadCount > 0 {
        HStack {
          Text(L10n.Contacts.Contacts.Detail.unreadMessages)
          Spacer()
          Text(state.currentContact.unreadCount, format: .number)
            .foregroundStyle(.blue)
        }
      }
    } header: {
      Text(L10n.Contacts.Contacts.Detail.info)
    }
  }

  private var isCurrentRenderer: Bool {
    state.rendererRevision == rendererRevision && state.rendererID == rendererID && state.hasPresentationOwnership
  }

  private var nicknameText: Binding<String> {
    Binding(
      get: { state.nickname },
      set: {
        guard isCurrentRenderer else { return }
        if state.nickname != $0 { pendingNicknameSelection = nil }
        state.nickname = $0
      }
    )
  }

  private var nicknameSelection: Binding<TextSelection?> {
    Binding(
      get: { renderedNicknameSelection },
      set: {
        guard isCurrentRenderer else { return }
        if pendingNicknameSelection != nil {
          guard nicknameFocused, $0 != nil else { return }
          // Retain selection until focus arrives; recreated fields initially report a default caret.
          renderedNicknameSelection = $0
          return
        }
        renderedNicknameSelection = $0
        guard nicknameFocused, let selection = $0 else { return }
        state.nicknameSelection = selection
      }
    )
  }

  private func restoreNicknameSelection() {
    guard let pendingNicknameSelection else { return }
    renderedNicknameSelection = pendingNicknameSelection
    self.pendingNicknameSelection = nil
  }

  private func saveNickname() {
    guard isCurrentRenderer else { return }
    state.perform(.nickname)
  }
}
