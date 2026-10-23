import MC1Services
import SwiftUI

struct DeviceConnectionMenu: View {
  let bluetooth: DeviceConnectionChoice.Link
  let wifi: DeviceConnectionChoice.Link
  let onSelect: (SavedDeviceConnectTarget) -> Void

  private static let hitTarget: CGFloat = 44

  var body: some View {
    Menu {
      item(bluetooth)
      item(wifi)
    } label: {
      Image(systemName: "ellipsis")
    }
    .buttonStyle(.bordered)
    .buttonBorderShape(.circle)
    .controlSize(.large)
    .frame(width: Self.hitTarget, height: Self.hitTarget)
    .accessibilityLabel(L10n.Settings.DeviceSelection.changeConnection)
  }

  @ViewBuilder
  private func item(_ link: DeviceConnectionChoice.Link) -> some View {
    let button = Button {
      guard !link.isCurrent, link.isEnabled else { return }
      onSelect(link.target)
    } label: {
      if link.isCurrent {
        Label(title(for: link), systemImage: "checkmark")
      } else {
        Text(title(for: link))
      }
    }
    .disabled(!link.isCurrent && !link.isEnabled)
    .accessibilityHint(hint(for: link))

    if link.isCurrent {
      button
        .accessibilityValue(L10n.Settings.Connection.Link.inUse)
        .accessibilityAddTraits(.isSelected)
    } else {
      button
    }
  }

  private func title(for link: DeviceConnectionChoice.Link) -> String {
    switch link.target {
    case .bluetooth:
      L10n.Settings.DeviceSelection.bluetooth
    case .wifi:
      L10n.Settings.DeviceSelection.wifi
    }
  }

  private func hint(for link: DeviceConnectionChoice.Link) -> String {
    if link.isCurrent {
      return ""
    }
    if !link.isEnabled {
      return L10n.Settings.DeviceSelection.Accessibility.outOfRangeHint
    }
    return L10n.Settings.DeviceSelection.Accessibility.selectHint
  }
}
