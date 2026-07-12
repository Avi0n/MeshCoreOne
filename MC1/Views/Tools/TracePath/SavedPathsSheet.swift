import MC1Services
import SwiftUI

/// Sheet displaying saved trace paths for selection
struct SavedPathsSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss
  @State private var viewModel = SavedPathsViewModel()

  /// Callback when a path is selected
  var onSelect: (SavedTracePathDTO) -> Void
  /// Callback when a path is deleted
  var onDelete: ((UUID) -> Void)?

  @State private var pathToDelete: SavedTracePathDTO?
  @State private var pathToRename: SavedTracePathDTO?
  @State private var renameText = ""

  var body: some View {
    NavigationStack {
      Group {
        if viewModel.savedPaths.isEmpty, !viewModel.isLoading {
          emptyState
        } else {
          pathsList
        }
      }
      .navigationTitle(L10n.Contacts.Contacts.SavedPaths.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Contacts.Contacts.Common.done) { dismiss() }
        }
      }
      .task {
        viewModel.configure(
          dataStore: { appState.services?.dataStore },
          connectedDevice: { appState.connectedDevice }
        )
        await viewModel.loadSavedPaths()
      }
      .errorAlert($viewModel.errorMessage)
      .confirmationDialog(
        L10n.Contacts.Contacts.SavedPaths.deleteTitle,
        isPresented: .init(
          get: { pathToDelete != nil },
          set: { if !$0 { pathToDelete = nil } }
        ),
        titleVisibility: .visible
      ) {
        Button(L10n.Contacts.Contacts.Common.delete, role: .destructive) {
          if let path = pathToDelete {
            let pathId = path.id
            Task {
              await viewModel.deletePath(path)
              onDelete?(pathId)
            }
          }
        }
      } message: {
        if let path = pathToDelete {
          Text(L10n.Contacts.Contacts.SavedPaths.deleteMessage(path.name))
        }
      }
      .alert(L10n.Contacts.Contacts.SavedPaths.renameTitle, isPresented: .init(
        get: { pathToRename != nil },
        set: { if !$0 { pathToRename = nil } }
      )) {
        TextField(L10n.Contacts.Contacts.Detail.name, text: $renameText)
        Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
        Button(L10n.Contacts.Contacts.Common.save) {
          if let path = pathToRename {
            Task { await viewModel.renamePath(path, to: renameText) }
          }
        }
      }
    }
    .presentationDetents([.medium, .large])
  }

  // MARK: - Empty State

  private var emptyState: some View {
    ContentUnavailableView {
      Label(L10n.Contacts.Contacts.SavedPaths.Empty.title, systemImage: "bookmark")
    } description: {
      Text(L10n.Contacts.Contacts.SavedPaths.Empty.description)
    }
  }

  // MARK: - Paths List

  /// `SavedPathRow` has no avatar, so the row text and the inter-row divider share this inset.
  private static let rowHorizontalPadding: CGFloat = 16

  private var pathsList: some View {
    ScrollView {
      LazyVStack(spacing: 0) {
        ForEach(Array(viewModel.savedPaths.enumerated()), id: \.element.id) { index, path in
          Button {
            onSelect(path)
            dismiss()
          } label: {
            SavedPathRow(path: path)
              .padding(.horizontal, Self.rowHorizontalPadding)
              .frame(maxWidth: .infinity, alignment: .leading)
              .contentShape(.rect)
          }
          .buttonStyle(.plain)
          .contextMenu {
            Button(L10n.Contacts.Contacts.SavedPaths.rename, systemImage: "pencil") {
              renameText = path.name
              pathToRename = path
            }
            Button(L10n.Contacts.Contacts.Common.delete, systemImage: "trash", role: .destructive) {
              pathToDelete = path
            }
          }
          .transition(.opacity)
          if index < viewModel.savedPaths.count - 1 {
            Divider().padding(.leading, Self.rowHorizontalPadding)
          }
        }
      }
    }
    .themedCanvas(theme)
    .overlay {
      if viewModel.isLoading {
        ProgressView()
      }
    }
  }
}
