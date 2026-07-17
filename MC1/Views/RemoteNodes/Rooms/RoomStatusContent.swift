import MC1Services
import SwiftUI

/// Stack-free room status body hosted by both the guest standalone sheet and the merged admin view.
///
/// The view model is owned as `@State` by the host. This content receives it as a plain `let` and must
/// never create, replace, or reset it; expand/loaded/loading/error state all live on the view model so
/// switching hosts or segments preserves it.
struct RoomStatusContent: View {
  @Environment(\.appTheme) private var theme

  let viewModel: RoomStatusViewModel
  let session: RemoteNodeSessionDTO
  let connectionState: DeviceConnectionState
  let connectedDeviceID: UUID?
  /// Clock drift measured at login; nil when no login carried the node's clock.
  var clockDrift: TimeInterval?

  var body: some View {
    List {
      NodeStatusHeaderSection(session: session, clockDrift: clockDrift)
      RoomStatusSection(viewModel: viewModel, session: session, connectionState: connectionState)
      NodeTelemetryDisclosureSection(helper: viewModel.helper, connectionState: connectionState) {
        await viewModel.requestTelemetry(for: session)
      }
      NodeBatteryCurveDisclosureSection(
        helper: viewModel.helper,
        session: session,
        connectionState: connectionState,
        connectedDeviceID: connectedDeviceID
      )
    }
    .nodeStatusDestinations(helper: viewModel.helper)
    .themedCanvas(theme)
    .nodeManagementHeaderTopMargin()
    .scrollDismissesKeyboard(.interactively)
  }
}

// MARK: - Status Section

private struct RoomStatusSection: View {
  let viewModel: RoomStatusViewModel
  let session: RemoteNodeSessionDTO
  let connectionState: DeviceConnectionState

  var body: some View {
    NodeStatusSection(helper: viewModel.helper, connectionState: connectionState) {
      await viewModel.requestStatus(for: session)
    } rows: {
      RoomStatusRows(viewModel: viewModel)
    }
  }
}

// MARK: - Status Rows

private struct RoomStatusRows: View {
  let viewModel: RoomStatusViewModel

  var body: some View {
    NodeCommonStatusRows(helper: viewModel.helper)
    NodePacketStatusRows(helper: viewModel.helper)
    LabeledContent(L10n.RemoteNodes.RemoteNodes.RoomStatus.postsReceived, value: viewModel.postsReceivedDisplay)
    LabeledContent(L10n.RemoteNodes.RemoteNodes.RoomStatus.postsPushed, value: viewModel.postsPushedDisplay)
  }
}
