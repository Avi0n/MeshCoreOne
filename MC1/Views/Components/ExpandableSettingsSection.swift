import SwiftUI

/// A collapsible section that auto-loads data when expanded
/// More iOS-native than explicit "Load" buttons
struct ExpandableSettingsSection<Content: View>: View {
  @Environment(\.appTheme) private var theme
  let title: String
  let icon: String

  @Binding var isExpanded: Bool
  let isLoaded: () -> Bool // Closure instead of binding (supports computed properties)
  @Binding var isLoading: Bool
  @Binding var hasError: Bool

  let onLoad: () async -> Void
  let footer: String?
  @ViewBuilder let content: () -> Content

  init(
    title: String,
    icon: String,
    isExpanded: Binding<Bool>,
    isLoaded: @escaping () -> Bool,
    isLoading: Binding<Bool>,
    hasError: Binding<Bool>,
    onLoad: @escaping () async -> Void,
    footer: String? = nil,
    @ViewBuilder content: @escaping () -> Content
  ) {
    self.title = title
    self.icon = icon
    _isExpanded = isExpanded
    self.isLoaded = isLoaded
    _isLoading = isLoading
    _hasError = hasError
    self.onLoad = onLoad
    self.footer = footer
    self.content = content
  }

  var body: some View {
    Section {
      DisclosureGroup(isExpanded: $isExpanded) {
        // Always show content - individual fields handle nil/loading states
        // with "loading..." overlays when their values haven't arrived yet
        content()

        // Show error banner if something failed
        if hasError, !isLoaded() {
          VStack(spacing: 12) {
            Label(L10n.Localizable.Common.Error.failedToLoad, systemImage: "exclamationmark.triangle")
              .foregroundStyle(.orange)
            Button(L10n.Localizable.Common.tryAgain) {
              Task { await onLoad() }
            }
            .buttonStyle(.bordered)
          }
          .frame(maxWidth: .infinity)
          .padding(.vertical, 8)
          .listRowSeparator(.hidden)
          .listRowInsets(EdgeInsets())
        }
      } label: {
        HStack {
          Label(title, systemImage: icon)
          Spacer()
          if isLoading {
            ProgressView()
              .scaleEffect(0.8)
              .padding(.trailing)
          } else if isExpanded, isLoaded() {
            Button {
              Task { await onLoad() }
            } label: {
              Image(systemName: "arrow.clockwise")
                .font(.caption)
            }
            .buttonStyle(.borderless)
            .padding(.trailing)
          }
        }
      }
    } footer: {
      if let footer {
        Text(footer)
      }
    }
    .themedRowBackground(theme)
    .onChange(of: isExpanded) { _, expanded in
      if expanded, !isLoaded(), !isLoading {
        Task { await onLoad() }
      }
    }
    .task {
      // Trigger initial load if section starts expanded
      // (onChange only fires when value changes, not on initial render)
      if isExpanded, !isLoaded(), !isLoading {
        await onLoad()
      }
    }
  }
}

#Preview {
  @Previewable @State var isExpanded = false
  @Previewable @State var isLoading = false
  @Previewable @State var hasError = false
  @Previewable @State var data: String?

  Form {
    ExpandableSettingsSection(
      title: "Device Info",
      icon: "info.circle",
      isExpanded: $isExpanded,
      isLoaded: { data != nil },
      isLoading: $isLoading,
      hasError: $hasError,
      onLoad: {
        isLoading = true
        try? await Task.sleep(for: .seconds(1))
        data = "Loaded!"
        isLoading = false
      }
    ) {
      Text(data ?? "")
    }
  }
}
