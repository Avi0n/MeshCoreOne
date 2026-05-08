import MC1Services
import SwiftUI

// MARK: - Unified Focus Field

enum NodeSettingsField: Hashable {
    case frequency, txPower, advertInterval, floodAdvertInterval, floodMaxHops
    case identityName, contactInfo, guestPassword
}

// MARK: - Status Header

struct NodeStatusHeaderSection: View {
    let session: RemoteNodeSessionDTO

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
                }
                Spacer()
            }
            .listRowBackground(Color.clear)
        }
    }
}

// MARK: - Settings Header

struct NodeSettingsHeaderSection: View {
    let publicKey: Data
    let name: String
    let role: RemoteNodeRole

    var body: some View {
        Section {
            HStack {
                Spacer()
                VStack(spacing: 8) {
                    NodeAvatar(publicKey: publicKey, role: role, size: 60)
                    Text(name)
                        .font(.headline)
                }
                Spacer()
            }
            .listRowBackground(Color.clear)
        }
    }
}

// MARK: - Common Status Rows

struct NodeCommonStatusRows: View {
    let helper: NodeStatusHelper

    var body: some View {
        NodeMetricRow(
            label: L10n.RemoteNodes.RemoteNodes.Status.battery,
            value: helper.batteryDisplay,
            delta: helper.batteryDeltaMV.map { Double($0) / 1000.0 },
            higherIsBetter: true, unit: " V", fractionDigits: 3
        )

        LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.uptime, value: helper.uptimeDisplay)

        LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.airtime, value: helper.airtimeDisplay)

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

        LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.packetsSent, value: helper.packetsSentDisplay)
        LabeledContent(L10n.RemoteNodes.RemoteNodes.Status.packetsReceived, value: helper.packetsReceivedDisplay)
    }
}

// MARK: - Status Section

struct NodeStatusSection<Rows: View>: View {
    let helper: NodeStatusHelper
    @ViewBuilder let rows: () -> Rows

    var body: some View {
        Section(L10n.RemoteNodes.RemoteNodes.Status.statusSection) {
            if helper.isLoadingStatus && helper.status == nil {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            } else if let errorMessage = helper.errorMessage, helper.status == nil {
                Text(errorMessage)
                    .foregroundStyle(.red)
            } else {
                rows()

                if let timestamp = helper.previousSnapshotTimestamp {
                    Text(timestamp)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                NavigationLink {
                    NodeStatusHistoryView(fetchSnapshots: helper.fetchHistory, ocvArray: helper.ocvValues)
                } label: {
                    Text(L10n.RemoteNodes.RemoteNodes.History.title)
                }
            }
        }
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
        if dataPoint.type == .voltage, case .float(let voltage) = dataPoint.value {
            let millivolts = Int(voltage * 1000)
            let battery = BatteryInfo(level: millivolts)
            let percentage = battery.percentage(using: ocvArray)

            LabeledContent(dataPoint.typeName) {
                VStack(alignment: .trailing, spacing: 2) {
                    Text(dataPoint.formattedValue)
                    Text("\(percentage)%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            LabeledContent(dataPoint.typeName, value: dataPoint.formattedValue)
        }
    }
}

// MARK: - Battery Curve Disclosure Section

struct NodeBatteryCurveDisclosureSection: View {
    @Bindable var helper: NodeStatusHelper
    let session: RemoteNodeSessionDTO
    let connectionState: ConnectionState
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
                        .foregroundStyle(.red)
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
    }
}

// MARK: - Telemetry Disclosure Section

struct NodeTelemetryDisclosureSection: View {
    @Bindable var helper: NodeStatusHelper
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
                } else if let errorMessage = helper.errorMessage, helper.telemetry == nil {
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

                    NavigationLink {
                        TelemetryHistoryView(fetchSnapshots: helper.fetchHistory, ocvArray: helper.ocvValues)
                    } label: {
                        Text(L10n.RemoteNodes.RemoteNodes.History.title)
                    }
                } else {
                    Text(L10n.RemoteNodes.RemoteNodes.Status.noTelemetryData)
                        .foregroundStyle(.secondary)
                }
            } label: {
                Text(L10n.RemoteNodes.RemoteNodes.Status.telemetry)
            }
            .onChange(of: helper.telemetryExpanded) { _, isExpanded in
                if isExpanded && !helper.telemetryLoaded {
                    Task {
                        await onRequestTelemetry()
                    }
                }
            }
        } footer: {
            Text(L10n.RemoteNodes.RemoteNodes.Status.telemetryFooter)
        }
    }
}

// MARK: - Device Info Section

struct NodeDeviceInfoSection: View {
    @Bindable var settings: NodeSettingsHelper

    var body: some View {
        ExpandableSettingsSection(
            title: L10n.RemoteNodes.RemoteNodes.Settings.deviceInfo,
            icon: "info.circle",
            isExpanded: $settings.isDeviceInfoExpanded,
            isLoaded: { settings.deviceInfoLoaded },
            isLoading: $settings.isLoadingDeviceInfo,
            hasError: $settings.deviceInfoError,
            onLoad: { await settings.fetchDeviceInfo() },
            footer: L10n.RemoteNodes.RemoteNodes.Settings.deviceInfoFooter
        ) {
            LabeledContent(L10n.RemoteNodes.RemoteNodes.Settings.firmware, value: settings.firmwareVersion ?? "\u{2014}")
            LabeledContent(L10n.RemoteNodes.RemoteNodes.Settings.deviceTime, value: settings.deviceTime ?? "\u{2014}")
        }
    }
}

// MARK: - Radio Settings Section

struct NodeRadioSettingsSection: View {
    @Bindable var settings: NodeSettingsHelper
    var focusedField: FocusState<NodeSettingsField?>.Binding
    var radioRestartWarning: String = L10n.RemoteNodes.RemoteNodes.Settings.radioRestartWarning

    var body: some View {
        ExpandableSettingsSection(
            title: L10n.RemoteNodes.RemoteNodes.Settings.radioParameters,
            icon: "antenna.radiowaves.left.and.right",
            isExpanded: $settings.isRadioExpanded,
            isLoaded: { settings.radioLoaded },
            isLoading: $settings.isLoadingRadio,
            hasError: $settings.radioError,
            onLoad: { await settings.fetchRadioSettings() },
            footer: L10n.RemoteNodes.RemoteNodes.Settings.radioFooter
        ) {
            if settings.radioSettingsModified {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.yellow)
                    Text(radioRestartWarning)
                        .font(.subheadline)
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(.yellow.opacity(0.1))
                .clipShape(.rect(cornerRadius: 8))
            }

            HStack {
                Text(L10n.RemoteNodes.RemoteNodes.Settings.frequencyMHz)
                Spacer()
                if let frequency = settings.frequency {
                    TextField(L10n.RemoteNodes.RemoteNodes.Settings.mhz, value: Binding(
                        get: { frequency },
                        set: { settings.frequency = $0 }
                    ), format: .number.precision(.fractionLength(3)).locale(.posix))
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 100)
                        .focused(focusedField, equals: .frequency)
                        .onChange(of: settings.frequency) { _, _ in
                            settings.radioSettingsModified = true
                        }
                } else {
                    Text(settings.isLoadingRadio ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.radioError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(width: 100, alignment: .trailing)
                }
            }

            if let bandwidth = settings.bandwidth {
                Picker(L10n.RemoteNodes.RemoteNodes.Settings.bandwidthKHz, selection: Binding(
                    get: { bandwidth },
                    set: { settings.bandwidth = $0 }
                )) {
                    ForEach(RadioOptions.bandwidthsKHz, id: \.self) { bwKHz in
                        Text(RadioOptions.formatBandwidth(UInt32(bwKHz * 1000)))
                            .tag(bwKHz)
                            .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Settings.Accessibility.bandwidthLabel(RadioOptions.formatBandwidth(UInt32(bwKHz * 1000))))
                    }
                }
                .pickerStyle(.menu)
                .tint(.primary)
                .accessibilityHint(L10n.RemoteNodes.RemoteNodes.Settings.bandwidthHint)
                .onChange(of: settings.bandwidth) { _, _ in
                    settings.radioSettingsModified = true
                }
            } else {
                HStack {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.bandwidthKHz)
                    Spacer()
                    Text(settings.isLoadingRadio ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.radioError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let spreadingFactor = settings.spreadingFactor {
                Picker(L10n.RemoteNodes.RemoteNodes.Settings.spreadingFactor, selection: Binding(
                    get: { spreadingFactor },
                    set: { settings.spreadingFactor = $0 }
                )) {
                    ForEach(RadioOptions.spreadingFactors, id: \.self) { sf in
                        Text(sf, format: .number)
                            .tag(sf)
                            .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Settings.Accessibility.spreadingFactorLabel(sf))
                    }
                }
                .pickerStyle(.menu)
                .tint(.primary)
                .accessibilityHint(L10n.RemoteNodes.RemoteNodes.Settings.spreadingFactorHint)
                .onChange(of: settings.spreadingFactor) { _, _ in
                    settings.radioSettingsModified = true
                }
            } else {
                HStack {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.spreadingFactor)
                    Spacer()
                    Text(settings.isLoadingRadio ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.radioError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let codingRate = settings.codingRate {
                Picker(L10n.RemoteNodes.RemoteNodes.Settings.codingRate, selection: Binding(
                    get: { codingRate },
                    set: { settings.codingRate = $0 }
                )) {
                    ForEach(RadioOptions.codingRates, id: \.self) { cr in
                        Text("\(cr)")
                            .tag(cr)
                            .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Settings.Accessibility.codingRateLabel(cr))
                    }
                }
                .pickerStyle(.menu)
                .tint(.primary)
                .accessibilityHint(L10n.RemoteNodes.RemoteNodes.Settings.codingRateHint)
                .onChange(of: settings.codingRate) { _, _ in
                    settings.radioSettingsModified = true
                }
            } else {
                HStack {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.codingRate)
                    Spacer()
                    Text(settings.isLoadingRadio ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.radioError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            HStack {
                Text(L10n.RemoteNodes.RemoteNodes.Settings.txPowerDbm)
                Spacer()
                if let txPower = settings.txPower {
                    TextField(L10n.RemoteNodes.RemoteNodes.Settings.dbm, value: Binding(
                        get: { txPower },
                        set: { settings.txPower = $0 }
                    ), format: .number)
                        .keyboardType(.numbersAndPunctuation)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                        .focused(focusedField, equals: .txPower)
                        .onChange(of: settings.txPower) { _, _ in
                            settings.radioSettingsModified = true
                        }
                } else {
                    Text(settings.isLoadingRadio ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.radioError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(width: 80, alignment: .trailing)
                }
            }

            Button {
                Task { await settings.applyRadioSettings() }
            } label: {
                AsyncActionLabel(isLoading: settings.isApplying, showSuccess: false) {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.applyRadioSettings)
                        .foregroundStyle(settings.radioSettingsModified ? Color.accentColor : .secondary)
                        .transition(.opacity)
                }
            }
            .disabled(!settings.radioSettingsModified || settings.isApplying)
        }
    }
}

// MARK: - Identity Section

struct RemoteNodeIdentitySection: View {
    @Bindable var settings: NodeSettingsHelper
    var focusedField: FocusState<NodeSettingsField?>.Binding
    var onPickLocation: () -> Void

    var body: some View {
        ExpandableSettingsSection(
            title: L10n.RemoteNodes.RemoteNodes.Settings.identityLocation,
            icon: "person.text.rectangle",
            isExpanded: $settings.isIdentityExpanded,
            isLoaded: { settings.identityLoaded },
            isLoading: $settings.isLoadingIdentity,
            hasError: $settings.identityError,
            onLoad: { await settings.fetchIdentity() },
            footer: L10n.RemoteNodes.RemoteNodes.Settings.identityFooter
        ) {
            HStack {
                Text(L10n.RemoteNodes.RemoteNodes.name)
                Spacer()
                if let name = settings.name {
                    TextField(L10n.RemoteNodes.RemoteNodes.name, text: Binding(
                        get: { name },
                        set: { settings.name = $0 }
                    ))
                    .multilineTextAlignment(.trailing)
                    .focused(focusedField, equals: .identityName)
                } else {
                    Text(settings.isLoadingIdentity ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.identityError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            HStack {
                Text(L10n.RemoteNodes.RemoteNodes.Settings.latitude)
                Spacer()
                if let latitude = settings.latitude {
                    TextField(L10n.RemoteNodes.RemoteNodes.Settings.latitude, value: Binding(
                        get: { latitude },
                        set: { settings.latitude = $0 }
                    ), format: .number.precision(.fractionLength(6)))
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 140)
                } else {
                    Text(settings.isLoadingIdentity ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.identityError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            HStack {
                Text(L10n.RemoteNodes.RemoteNodes.Settings.longitude)
                Spacer()
                if let longitude = settings.longitude {
                    TextField(L10n.RemoteNodes.RemoteNodes.Settings.longitude, value: Binding(
                        get: { longitude },
                        set: { settings.longitude = $0 }
                    ), format: .number.precision(.fractionLength(6)))
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 140)
                } else {
                    Text(settings.isLoadingIdentity ? L10n.RemoteNodes.RemoteNodes.Settings.loading : (settings.identityError ? L10n.RemoteNodes.RemoteNodes.Settings.failedToLoad : "—"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Button(L10n.RemoteNodes.RemoteNodes.Settings.pickOnMap, systemImage: "map") {
                onPickLocation()
            }

            Button {
                Task { await settings.applyIdentitySettings() }
            } label: {
                AsyncActionLabel(isLoading: settings.isApplying, showSuccess: settings.identityApplySuccess) {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.applyIdentitySettings)
                }
            }
            .disabled(!settings.identitySettingsModified || settings.isApplying)
        }
    }
}

// MARK: - Contact Info Section

struct NodeContactInfoSection: View {
    @Bindable var settings: NodeSettingsHelper
    var focusedField: FocusState<NodeSettingsField?>.Binding
    @State private var contactText = ""

    var body: some View {
        ExpandableSettingsSection(
            title: L10n.RemoteNodes.RemoteNodes.Settings.contactInfo,
            icon: "person.crop.rectangle",
            isExpanded: $settings.isContactInfoExpanded,
            isLoaded: { settings.contactInfoLoaded },
            isLoading: $settings.isLoadingContactInfo,
            hasError: $settings.contactInfoError,
            onLoad: { await settings.fetchContactInfo() },
            footer: L10n.RemoteNodes.RemoteNodes.Settings.contactInfoFooter
        ) {
            TextField(L10n.RemoteNodes.RemoteNodes.Settings.contactInfoPlaceholder, text: $contactText, axis: .vertical)
                .lineLimit(3...6)
                .focused(focusedField, equals: .contactInfo)
                .overlay(alignment: .bottomTrailing) {
                    Text("\(settings.ownerInfoCharCount)/119")
                        .font(.caption2)
                        .foregroundStyle(settings.ownerInfoCharCount > 119 ? .red : .secondary)
                        .padding(4)
                }
                .onChange(of: settings.ownerInfo, initial: true) { _, newValue in
                    contactText = newValue ?? ""
                }
                .onChange(of: contactText) { _, newValue in
                    settings.ownerInfo = newValue
                }

            Button {
                Task { await settings.applyContactInfoSettings() }
            } label: {
                AsyncActionLabel(isLoading: settings.isApplying, showSuccess: settings.contactInfoApplySuccess) {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.applyContactInfo)
                }
            }
            .disabled(!settings.contactInfoSettingsModified || settings.isApplying || settings.ownerInfoCharCount > 119)
        }
    }
}

// MARK: - Security Section

struct NodeSecuritySection: View {
    @Bindable var settings: NodeSettingsHelper

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $settings.isSecurityExpanded) {
                SecureField(L10n.RemoteNodes.RemoteNodes.Settings.newPassword, text: $settings.newPassword)
                SecureField(L10n.RemoteNodes.RemoteNodes.Settings.confirmPassword, text: $settings.confirmPassword)

                Button {
                    Task { await settings.changePassword() }
                } label: {
                    AsyncActionLabel(isLoading: settings.isApplying, showSuccess: settings.changePasswordSuccess) {
                        Text(L10n.RemoteNodes.RemoteNodes.Settings.changePassword)
                    }
                }
                .disabled(settings.isApplying || settings.changePasswordSuccess || settings.newPassword.isEmpty || settings.newPassword != settings.confirmPassword)
            } label: {
                Label(L10n.RemoteNodes.RemoteNodes.Settings.security, systemImage: "lock")
            }
        } footer: {
            Text(L10n.RemoteNodes.RemoteNodes.Settings.securityFooter)
        }
    }
}

// MARK: - Actions Section

struct NodeActionsSection: View {
    let settings: NodeSettingsHelper
    @Binding var showRebootConfirmation: Bool
    var rebootConfirmTitle: String = L10n.RemoteNodes.RemoteNodes.Settings.rebootConfirmTitle
    var rebootMessage: String = L10n.RemoteNodes.RemoteNodes.Settings.rebootMessage

    var body: some View {
        Section(L10n.RemoteNodes.RemoteNodes.Settings.deviceActions) {
            Button {
                Task { await settings.forceAdvert() }
            } label: {
                HStack {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.sendAdvert)
                    if settings.isSendingAdvert {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(settings.isSendingAdvert)

            Button {
                Task { await settings.syncTime() }
            } label: {
                HStack {
                    Text(L10n.RemoteNodes.RemoteNodes.Settings.syncTime)
                    if settings.isApplying {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(settings.isApplying)

            Button(L10n.RemoteNodes.RemoteNodes.Settings.rebootDevice, role: .destructive) {
                showRebootConfirmation = true
            }
            .disabled(settings.isRebooting)
            .confirmationDialog(rebootConfirmTitle, isPresented: $showRebootConfirmation) {
                Button(L10n.RemoteNodes.RemoteNodes.Settings.reboot, role: .destructive) {
                    Task { await settings.reboot() }
                }
                Button(L10n.RemoteNodes.RemoteNodes.cancel, role: .cancel) { }
            } message: {
                Text(rebootMessage)
            }

            if let error = settings.errorMessage {
                Text(error)
                    .foregroundStyle(.primary)
                    .font(.caption)
            }
        }
    }
}
