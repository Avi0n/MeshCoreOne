import MapLibre
import MC1Services
import SwiftUI

struct OfflineMapSettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var showingRegionPicker = false

  var body: some View {
    Group {
      if appState.offlineMapService.packs.isEmpty {
        ContentUnavailableView {
          Label(L10n.Settings.OfflineMaps.emptyTitle, systemImage: "map")
        } description: {
          Text(L10n.Settings.OfflineMaps.emptyDescription)
        } actions: {
          Button(L10n.Settings.OfflineMaps.downloadRegion, systemImage: "arrow.down.circle") {
            showingRegionPicker = true
          }
          .buttonStyle(.bordered)
        }
      } else {
        List {
          PacksSection()
          StorageSection()
        }
        .themedCanvas(theme)
        .toolbar {
          ToolbarItem(placement: .primaryAction) {
            Button(L10n.Settings.OfflineMaps.downloadRegion, systemImage: "plus") {
              showingRegionPicker = true
            }
          }
        }
      }
    }
    .navigationTitle(L10n.Settings.OfflineMaps.title)
    .sheet(isPresented: $showingRegionPicker) {
      RegionPickerSheet()
    }
  }
}

// MARK: - Packs Section

private struct PacksSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  var body: some View {
    Section {
      ForEach(appState.offlineMapService.packs) { pack in
        OfflinePackRow(pack: pack)
      }
      .onDelete { indexSet in
        if let index = indexSet.first {
          let pack = appState.offlineMapService.packs[index]
          Task { await appState.offlineMapService.deletePack(pack) }
        }
      }
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Storage Section

private struct StorageSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  var body: some View {
    Section {
      LabeledContent(L10n.Settings.OfflineMaps.storageUsed) {
        Text(appState.offlineMapService.databaseSize, format: .byteCount(style: .file))
      }
    } header: {
      Text(L10n.Settings.OfflineMaps.storage)
    } footer: {
      Text(L10n.Settings.OfflineMaps.storageFooter)
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Offline Pack Row

private struct OfflinePackRow: View {
  @Environment(\.appState) private var appState
  let pack: OfflinePack

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      HStack {
        Text(pack.name)
        Text("— \(pack.layer.label)")
          .foregroundStyle(.secondary)
      }

      HStack {
        if pack.isComplete {
          Text(L10n.Settings.OfflineMaps.complete)
            .foregroundStyle(.secondary)
        } else if pack.isPaused {
          Text(L10n.Settings.OfflineMaps.paused)
            .foregroundStyle(.secondary)
        } else {
          Text(L10n.Settings.OfflineMaps.downloading)
            .foregroundStyle(.secondary)
        }

        Spacer()

        VStack(alignment: .trailing) {
          Text(Int64(pack.completedBytes), format: .byteCount(style: .file))
          if let speed = pack.downloadSpeed, speed > 0 {
            Text("\(speed, format: .byteCount(style: .file))/s")
          }
        }
        .foregroundStyle(.secondary)
      }
      .font(.caption)

      if !pack.isComplete {
        HStack {
          ProgressView(value: pack.completedFraction)

          Button(
            pack.isPaused
              ? L10n.Settings.OfflineMaps.resume
              : L10n.Settings.OfflineMaps.pause,
            systemImage: pack.isPaused ? "play.fill" : "pause.fill"
          ) {
            if pack.isPaused {
              appState.offlineMapService.resumePack(pack)
            } else {
              appState.offlineMapService.pausePack(pack)
            }
          }
          .labelStyle(.iconOnly)
          .buttonStyle(.borderless)
        }
      }
    }
  }
}

#Preview {
  NavigationStack {
    OfflineMapSettingsView()
      .environment(\.appState, AppState())
  }
}
