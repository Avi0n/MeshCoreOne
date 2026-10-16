import MC1Services
import SwiftUI

struct NodeConfigExportView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var viewModel = NodeConfigExportViewModel()

  var body: some View {
    List {
      Section {
        Toggle(
          L10n.Settings.ConfigExport.selectAll,
          isOn: Binding(
            get: { viewModel.sections.allSelected },
            set: { newValue in
              if newValue {
                viewModel.sections.selectAll()
              } else {
                viewModel.sections.deselectAll()
              }
            }
          )
        )
        .bold()

        ExportToggleRow(
          L10n.Settings.ConfigExport.nodeIdentity,
          description: L10n.Settings.ConfigExport.NodeIdentity.description,
          isOn: $viewModel.sections.nodeIdentity
        )
        ExportToggleRow(
          L10n.Settings.ConfigExport.radioSettings,
          description: L10n.Settings.ConfigExport.RadioSettings.description,
          isOn: $viewModel.sections.radioSettings
        )
        ExportToggleRow(
          L10n.Settings.ConfigExport.positionSettings,
          description: L10n.Settings.ConfigExport.PositionSettings.description,
          isOn: $viewModel.sections.positionSettings
        )
        ExportToggleRow(
          L10n.Settings.ConfigExport.otherSettings,
          description: L10n.Settings.ConfigExport.OtherSettings.description,
          isOn: $viewModel.sections.otherSettings
        )
        ExportToggleRow(
          L10n.Settings.ConfigExport.channels,
          description: L10n.Settings.ConfigExport.Channels.description,
          isOn: $viewModel.sections.channels
        )
        ExportToggleRow(
          L10n.Settings.ConfigExport.contacts,
          description: L10n.Settings.ConfigExport.Contacts.description,
          isOn: $viewModel.sections.contacts
        )
      }
      .themedRowBackground(theme)

      Section {
        Button {
          Task {
            await viewModel.exportConfig(
              nodeConfigService: appState.services?.nodeConfigService,
              deviceNodeName: appState.connectedDevice?.nodeName
            )
          }
        } label: {
          HStack {
            Text(viewModel.sections.allSelected
              ? L10n.Settings.ConfigExport.exportFull
              : L10n.Settings.ConfigExport.exportSelected)
            Spacer()
            if viewModel.isExporting {
              ProgressView()
            }
          }
        }
        .disabled(viewModel.isExporting || !viewModel.sections.anySectionSelected)
      }
      .themedRowBackground(theme)

      if let error = viewModel.errorMessage {
        Section {
          Label(error, systemImage: "exclamationmark.triangle")
            .foregroundStyle(.red)
        }
        .themedRowBackground(theme)
      }
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.ConfigExport.title)
    .fileExporter(
      isPresented: $viewModel.showFileExporter,
      document: viewModel.exportedDocument,
      contentType: .json,
      defaultFilename: viewModel.exportedDocument?.filename
    ) { result in
      if case let .failure(error) = result {
        viewModel.errorMessage = error.userFacingMessage
      }
    }
  }
}

private struct ExportToggleRow: View {
  let title: String
  let description: String
  @Binding var isOn: Bool

  init(_ title: String, description: String, isOn: Binding<Bool>) {
    self.title = title
    self.description = description
    _isOn = isOn
  }

  var body: some View {
    Toggle(isOn: $isOn) {
      VStack(alignment: .leading) {
        Text(title)
        Text(description)
          .font(.footnote)
          .foregroundStyle(.secondary)
      }
    }
  }
}
