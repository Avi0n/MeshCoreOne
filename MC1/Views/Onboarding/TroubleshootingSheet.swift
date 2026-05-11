import SwiftUI
import MC1Services
import UIKit

/// Troubleshooting sheet for when devices don't appear in the ASK picker
/// Per Apple Developer Forums: Factory-reset devices won't appear until the stale
/// system pairing is removed via removeAccessory()
struct TroubleshootingSheet: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var isClearing = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label(L10n.Onboarding.Troubleshooting.BasicChecks.powerOn, systemImage: "power")
                    Label(L10n.Onboarding.Troubleshooting.BasicChecks.moveCloser, systemImage: "iphone.radiowaves.left.and.right")
                    Label(L10n.Onboarding.Troubleshooting.BasicChecks.notConnectedElsewhere, systemImage: "app.dashed")
                    Label(L10n.Onboarding.Troubleshooting.BasicChecks.restart, systemImage: "arrow.clockwise")
                } header: {
                    Text(L10n.Onboarding.Troubleshooting.BasicChecks.header)
                }

                Section {
                    VStack(alignment: .leading, spacing: OnboardingMetrics.titleStackSpacing) {
                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.explanation)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.confirmationNote)
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        Button {
                            clearStalePairings()
                        } label: {
                            HStack {
                                if isClearing {
                                    ProgressView()
                                        .controlSize(.small)
                                } else {
                                    Image(systemName: "trash")
                                }
                                Text(L10n.Onboarding.Troubleshooting.FactoryReset.clearPairing)
                            }
                        }
                        .disabled(isClearing || appState.connectionManager.pairedAccessoriesCount == 0)
                    }
                } header: {
                    Text(L10n.Onboarding.Troubleshooting.FactoryReset.header)
                } footer: {
                    if appState.connectionManager.pairedAccessoriesCount == 0 {
                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.noPairings)
                    } else {
                        Text(L10n.Onboarding.Troubleshooting.FactoryReset.pairingsFound(appState.connectionManager.pairedAccessoriesCount))
                    }
                }

                Section {
                    VStack(alignment: .leading, spacing: OnboardingMetrics.titleStackSpacing) {
                        Text(L10n.Onboarding.Troubleshooting.SystemSettings.manageAccessories)
                            .font(.subheadline)
                        Text(L10n.Onboarding.Troubleshooting.SystemSettings.path)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                openURL(url)
                            }
                        } label: {
                            Label(
                                L10n.Onboarding.Troubleshooting.SystemSettings.openSettings,
                                systemImage: "gear"
                            )
                        }
                    }
                } header: {
                    Text(L10n.Onboarding.Troubleshooting.SystemSettings.header)
                }

                Section {
                    Text(L10n.Onboarding.Troubleshooting.StillNotAppearing.body)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } header: {
                    Text(L10n.Onboarding.Troubleshooting.StillNotAppearing.header)
                }
            }
            .navigationTitle(L10n.Onboarding.Troubleshooting.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.Localizable.Common.done) {
                        dismiss()
                    }
                }
            }
        }
    }

    private func clearStalePairings() {
        isClearing = true

        Task {
            defer { isClearing = false }

            await appState.connectionManager.clearStalePairings()

            dismiss()
        }
    }
}
