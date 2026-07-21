import MC1Services
import SwiftUI

// MARK: - Unified Focus Field

enum NodeSettingsField: Hashable {
  case frequency, advertInterval, floodAdvertInterval, floodMaxHops
  case identityName, contactInfo, guestPassword
}

// MARK: - Settings Header

private let nodeHeaderTopContentMargin: CGFloat = 8

extension View {
  /// Trims the grouped scroll view's default top inset so the node header avatar sits close to
  /// the pinned management tab picker. Applied to the settings `Form` and telemetry `List` that
  /// host `NodeSettingsHeaderSection` / `NodeStatusHeaderSection`.
  func nodeManagementHeaderTopMargin() -> some View {
    contentMargins(.top, nodeHeaderTopContentMargin, for: .scrollContent)
  }
}

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
      .listRowInsets(EdgeInsets())
    }
    .listSectionSpacing(.compact)
  }
}

// MARK: - Device Info Section

struct NodeDeviceInfoSection: View {
  @Bindable var settings: NodeSettingsViewModel

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
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Settings.firmware, value: settings.firmwareVersion ?? NodeStatusViewModel.emDash)
      LabeledContent(L10n.RemoteNodes.RemoteNodes.Settings.deviceTime, value: settings.deviceTime ?? NodeStatusViewModel.emDash)
    }
  }
}

// MARK: - Radio Settings Section

struct NodeRadioSettingsSection: View {
  @Bindable var settings: NodeSettingsViewModel
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
          SettingsLoadPlaceholder(isLoading: settings.isLoadingRadio, hasError: settings.radioError)
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
          SettingsLoadPlaceholder(isLoading: settings.isLoadingRadio, hasError: settings.radioError)
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
          SettingsLoadPlaceholder(isLoading: settings.isLoadingRadio, hasError: settings.radioError)
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
          SettingsLoadPlaceholder(isLoading: settings.isLoadingRadio, hasError: settings.radioError)
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
  @Bindable var settings: NodeSettingsViewModel
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
          SettingsLoadPlaceholder(isLoading: settings.isLoadingIdentity, hasError: settings.identityError)
        }
      }

      if let error = settings.nameError {
        Text(error)
          .font(.caption)
          .foregroundStyle(.red)
      }

      HStack {
        Text(L10n.RemoteNodes.RemoteNodes.Settings.latitude)
        Spacer()
        if let latitude = settings.latitude {
          TextField(L10n.RemoteNodes.RemoteNodes.Settings.latitude, value: Binding(
            get: { latitude },
            set: { settings.latitude = $0 }
          ), format: .number.precision(.fractionLength(6)).locale(.posix))
            .keyboardType(.numbersAndPunctuation)
            .multilineTextAlignment(.trailing)
            .frame(width: 140)
        } else {
          SettingsLoadPlaceholder(isLoading: settings.isLoadingIdentity, hasError: settings.identityError)
        }
      }

      if let error = settings.latitudeError {
        Text(error)
          .font(.caption)
          .foregroundStyle(.red)
      }

      HStack {
        Text(L10n.RemoteNodes.RemoteNodes.Settings.longitude)
        Spacer()
        if let longitude = settings.longitude {
          TextField(L10n.RemoteNodes.RemoteNodes.Settings.longitude, value: Binding(
            get: { longitude },
            set: { settings.longitude = $0 }
          ), format: .number.precision(.fractionLength(6)).locale(.posix))
            .keyboardType(.numbersAndPunctuation)
            .multilineTextAlignment(.trailing)
            .frame(width: 140)
        } else {
          SettingsLoadPlaceholder(isLoading: settings.isLoadingIdentity, hasError: settings.identityError)
        }
      }

      if let error = settings.longitudeError {
        Text(error)
          .font(.caption)
          .foregroundStyle(.red)
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
  @Bindable var settings: NodeSettingsViewModel
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
          Text("\(settings.ownerInfoCharCount)/\(NodeSettingsViewModel.ownerInfoMaxLength)")
            .font(.caption2)
            .foregroundStyle(settings.isOwnerInfoTooLong ? .red : .secondary)
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
      .disabled(!settings.contactInfoSettingsModified || settings.isApplying || settings.isOwnerInfoTooLong)
    }
  }
}

// MARK: - Security Section

struct NodeSecuritySection: View {
  @Environment(\.appTheme) private var theme
  @Bindable var settings: NodeSettingsViewModel

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
    .themedRowBackground(theme)
  }
}

// MARK: - Actions Section

struct NodeActionsSection: View {
  @Environment(\.appTheme) private var theme
  let settings: NodeSettingsViewModel
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
        Button(L10n.RemoteNodes.RemoteNodes.cancel, role: .cancel) {}
      } message: {
        Text(rebootMessage)
      }

      if let error = settings.errorMessage {
        Text(error)
          .foregroundStyle(.orange)
          .font(.caption)
      }
    }
    .themedRowBackground(theme)
  }
}
