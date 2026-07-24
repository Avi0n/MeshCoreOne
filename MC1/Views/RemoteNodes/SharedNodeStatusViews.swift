import MC1Services
import SwiftUI

// MARK: - Status Header

struct NodeStatusHeaderSection: View {
  /// Clock drift below this magnitude is normal RTC scatter and not shown.
  private static let clockDriftWarningThreshold: TimeInterval = 300

  let session: RemoteNodeSessionDTO
  var clockDrift: TimeInterval?

  var body: some View {
    Section {
      HStack {
        Spacer()
        VStack(spacing: 8) {
          NodeAvatar(publicKey: session.publicKey, role: session.role, size: 60)

          Text(session.name)
            .font(.headline)

          if session.permissionLevel == .guest {
            Text(L10n.RemoteNodes.RemoteNodes.Status.guestMode)
              .font(.subheadline)
              .foregroundStyle(.secondary)
          }

          if let drift = clockDrift, abs(drift) >= Self.clockDriftWarningThreshold {
            Label(clockDriftWarning(drift), systemImage: "clock.badge.exclamationmark")
              .font(.footnote)
              .foregroundStyle(.orange)
              .multilineTextAlignment(.center)
          }
        }
        Spacer()
      }
      .listRowBackground(Color.clear)
      .listRowInsets(EdgeInsets())
    }
    .listSectionSpacing(.compact)
  }

  private func clockDriftWarning(_ drift: TimeInterval) -> String {
    let magnitude = Duration.seconds(abs(drift)).formatted(
      .units(allowed: [.days, .hours, .minutes, .seconds], width: .abbreviated, maximumUnitCount: 2)
    )
    return drift > 0
      ? L10n.RemoteNodes.RemoteNodes.Status.clockAhead(magnitude)
      : L10n.RemoteNodes.RemoteNodes.Status.clockBehind(magnitude)
  }
}

// MARK: - Common Status Rows

struct NodeCommonStatusRows: View {
  let helper: NodeStatusViewModel

  var body: some View {
    NodeMetricRow(
      label: L10n.RemoteNodes.RemoteNodes.Status.battery,
      value: helper.batteryDisplay,
      delta: helper.batteryDeltaMV.map { Double($0) / 1000.0 },
      higherIsBetter: true, unit: " V", fractionDigits: 3
    )

    LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.uptime, value: helper.uptimeDisplay)

    LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.airtime, value: helper.airtimeDisplay)

    LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.airtimePercent, value: helper.airtimePercentDisplay)

    NodeMetricRow(
      label: L10n.RemoteNodes.RemoteNodes.Status.lastRssi,
      value: helper.lastRSSIDisplay,
      delta: helper.rssiDelta.map(Double.init),
      higherIsBetter: true, unit: " dBm", fractionDigits: 0
    )

    NodeMetricRow(
      label: L10n.RemoteNodes.RemoteNodes.Status.lastSnr,
      value: helper.lastSNRDisplay,
      delta: helper.snrDelta,
      higherIsBetter: true, unit: " dB", fractionDigits: 1
    )

    NodeMetricRow(
      label: L10n.RemoteNodes.RemoteNodes.Status.noiseFloor,
      value: helper.noiseFloorDisplay,
      delta: helper.noiseFloorDelta.map(Double.init),
      higherIsBetter: false, unit: " dBm", fractionDigits: 0
    )
  }
}

// MARK: - Packet Status Section

/// The packet counters grouped under a `Packets` header: sent/received totals, their
/// Direct/Flood breakdown, duplicates, and the optional receive-error count (repeaters
/// only). The header supplies the shared noun so each row label stays a short leaf.
struct NodePacketStatusRows: View {
  let helper: NodeStatusViewModel
  var receiveErrorsDisplay: String?

  var body: some View {
    Section {
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.packetsSent, value: helper.packetsSentDisplay)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.packetsReceived, value: helper.packetsReceivedDisplay)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.sentDirect, value: helper.sentDirectDisplay)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.sentFlood, value: helper.sentFloodDisplay)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.receivedDirect, value: helper.receivedDirectDisplay)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.receivedFlood, value: helper.receivedFloodDisplay)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.duplicates, value: helper.duplicatesDisplay)
      if let receiveErrorsDisplay {
        LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.receiveErrors, value: receiveErrorsDisplay)
      }
    } header: {
      Text(L10n.RemoteNodes.RemoteNodes.Status.packets)
        .fontWeight(.semibold)
    }
  }
}

// MARK: - Status Section

struct NodeStatusSection<Rows: View>: View {
  @Environment(\.appTheme) private var theme
  @Bindable var helper: NodeStatusViewModel
  let connectionState: DeviceConnectionState
  let onLoad: () async -> Void
  @ViewBuilder let rows: () -> Rows

  var body: some View {
    Section {
      DisclosureGroup(isExpanded: $helper.statusExpanded) {
        if helper.isLoadingStatus, helper.status == nil {
          HStack {
            Spacer()
            ProgressView()
            Spacer()
          }
        } else if let errorMessage = helper.statusSectionError, helper.status == nil {
          Text(errorMessage)
            .foregroundStyle(.orange)
        } else if helper.status != nil {
          rows()

          if let timestamp = helper.previousSnapshotTimestamp {
            Text(timestamp)
              .font(.caption)
              .foregroundStyle(.secondary)
          }

          NavigationLink(value: NodeStatusRoute.statusHistory) {
            Text(L10n.RemoteNodes.RemoteNodes.History.title)
          }
        }
      } label: {
        HStack {
          Text(L10n.RemoteNodes.RemoteNodes.Status.statusSection)
          Spacer()
          SectionReloadButton(
            isLoading: helper.isLoadingStatus,
            isLoaded: helper.statusLoaded,
            hasError: helper.statusSectionError != nil,
            isDisabled: connectionState != .ready,
            accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.Accessibility.reloadStatus,
            onReload: onLoad
          )
        }
      }
      .onChange(of: helper.statusExpanded) { _, isExpanded in
        if isExpanded, !helper.statusLoaded, !helper.isLoadingStatus {
          Task {
            await onLoad()
          }
        }
      }
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Metric Row

struct NodeMetricRow: View {
  let label: String
  let value: String
  let delta: Double?
  let higherIsBetter: Bool
  let unit: String
  let fractionDigits: Int

  var body: some View {
    LabeledContent {
      VStack(alignment: .trailing, spacing: 2) {
        Text(value)
        if let delta {
          StatusDeltaView(delta: delta, higherIsBetter: higherIsBetter, unit: unit, fractionDigits: fractionDigits)
        }
      }
    } label: {
      Text(label)
    }
  }
}

// MARK: - Telemetry Row

struct NodeTelemetryRow: View {
  let dataPoint: LPPDataPoint
  let ocvArray: [Int]

  var body: some View {
    if dataPoint.type == .voltage, case let .float(voltage) = dataPoint.value {
      let millivolts = Int(voltage * 1000)
      let battery = BatteryInfo(level: millivolts)
      let percentage = battery.percentage(using: ocvArray)

      LabeledContent(dataPoint.type.localizedName) {
        VStack(alignment: .trailing, spacing: 2) {
          Text(dataPoint.formattedValue)
          Text("\(percentage)%")
            .font(.caption)
            .foregroundStyle(.secondary)
        }
      }
    } else {
      LabeledContent(dataPoint.type.localizedName, value: dataPoint.formattedValue)
    }
  }
}

// MARK: - Battery Curve Disclosure Section

struct NodeBatteryCurveDisclosureSection: View {
  @Environment(\.appTheme) private var theme
  @Bindable var helper: NodeStatusViewModel
  let session: RemoteNodeSessionDTO
  let connectionState: DeviceConnectionState
  let connectedDeviceID: UUID?

  var body: some View {
    Section {
      DisclosureGroup(isExpanded: $helper.isBatteryCurveExpanded) {
        BatteryCurveSection(
          availablePresets: OCVPreset.nodePresets,
          headerText: "",
          footerText: "",
          selectedPreset: $helper.selectedOCVPreset,
          voltageValues: $helper.ocvValues,
          onSave: helper.saveOCVSettings,
          isDisabled: connectionState != .ready
        )

        if let error = helper.ocvError {
          Text(error)
            .font(.caption)
            .foregroundStyle(.orange)
        }
      } label: {
        Text(L10n.RemoteNodes.RemoteNodes.Status.batteryCurve)
      }
      .onChange(of: helper.isBatteryCurveExpanded) { _, isExpanded in
        if isExpanded, let deviceID = connectedDeviceID {
          Task {
            await helper.loadOCVSettings(publicKey: session.publicKey, radioID: deviceID)
          }
        }
      }
    } footer: {
      Text(L10n.RemoteNodes.RemoteNodes.Status.batteryCurveFooter)
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Telemetry Disclosure Section

struct NodeTelemetryDisclosureSection: View {
  @Environment(\.appTheme) private var theme
  @Bindable var helper: NodeStatusViewModel
  let connectionState: DeviceConnectionState
  let onRequestTelemetry: () async -> Void

  var body: some View {
    Section {
      DisclosureGroup(isExpanded: $helper.telemetryExpanded) {
        if helper.isLoadingTelemetry {
          HStack {
            Spacer()
            ProgressView()
            Spacer()
          }
        } else if let errorMessage = helper.telemetrySectionError, helper.telemetry == nil {
          Text(errorMessage)
            .foregroundStyle(.orange)
        } else if helper.telemetry != nil {
          if helper.cachedDataPoints.isEmpty {
            Text(L10n.RemoteNodes.RemoteNodes.Status.noSensorData)
              .foregroundStyle(.secondary)
          } else if helper.hasMultipleChannels {
            ForEach(helper.groupedDataPoints, id: \.channel) { group in
              Section {
                ForEach(group.dataPoints, id: \.self) { dataPoint in
                  NodeTelemetryRow(dataPoint: dataPoint, ocvArray: helper.ocvValues)
                }
              } header: {
                Text(L10n.RemoteNodes.RemoteNodes.Status.channel(Int(group.channel)))
                  .fontWeight(.semibold)
              }
            }
          } else {
            ForEach(helper.cachedDataPoints, id: \.self) { dataPoint in
              NodeTelemetryRow(dataPoint: dataPoint, ocvArray: helper.ocvValues)
            }
          }

          if let fix = helper.currentLocationFix {
            NavigationLink(value: NodeStatusRoute.locationMap(fix: fix, name: helper.session?.name)) {
              Label(L10n.RemoteNodes.RemoteNodes.Status.viewOnMap, systemImage: "map")
            }
            .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Status.Accessibility.viewLocationOnMap)
          }

          NavigationLink(value: NodeStatusRoute.telemetryHistory) {
            Text(L10n.RemoteNodes.RemoteNodes.History.title)
          }
        } else {
          Text(L10n.RemoteNodes.RemoteNodes.Status.noTelemetryData)
            .foregroundStyle(.secondary)
        }
      } label: {
        HStack {
          Text(L10n.RemoteNodes.RemoteNodes.Status.telemetry)
          Spacer()
          SectionReloadButton(
            isLoading: helper.isLoadingTelemetry,
            isLoaded: helper.telemetryLoaded,
            hasError: helper.telemetrySectionError != nil,
            isDisabled: connectionState != .ready,
            accessibilityLabel: L10n.RemoteNodes.RemoteNodes.Status.Accessibility.reloadTelemetry,
            onReload: onRequestTelemetry
          )
        }
      }
      .onChange(of: helper.telemetryExpanded) { _, isExpanded in
        if isExpanded, !helper.telemetryLoaded, !helper.isLoadingTelemetry {
          Task {
            await onRequestTelemetry()
          }
        }
      }
    } footer: {
      Text(L10n.RemoteNodes.RemoteNodes.Status.telemetryFooter)
    }
    .themedRowBackground(theme)
  }
}
