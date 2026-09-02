import MC1Services
import SwiftUI

struct LanguageSettingsView: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.locale) private var locale
  @Environment(\.openURL) private var openURL
  @AppStorage(AppStorageKey.translationTargetLanguage.rawValue)
  private var translationTargetLanguage = AppStorageKey.defaultTranslationTargetLanguage
  @AppStorage(AppStorageKey.useDefaultTranslationApp.rawValue)
  private var storedUseDefaultTranslationApp = AppStorageKey.defaultUseDefaultTranslationApp

  private var preference: TranslationTargetPreference {
    TranslationTargetPreference(rawValue: translationTargetLanguage)
  }

  private var usesSystemOverlay: Bool {
    TranslationTargetPreference.usesSystemOverlay(
      languageRawValue: translationTargetLanguage,
      useDefaultTranslationApp: storedUseDefaultTranslationApp
    )
  }

  var body: some View {
    List {
      appLanguageSection
      translationSection
    }
    .themedCanvas(theme)
    .settingsSubpageDestinations()
    .navigationTitle(L10n.Settings.Language.title)
    .navigationBarTitleDisplayMode(.inline)
    .onAppear {
      if preference.isSystemOverlay {
        storedUseDefaultTranslationApp = true
        replaceOverlaySentinelWithAppLanguage()
      }
    }
  }

  private var appLanguageSection: some View {
    Section {
      Button {
        if let url = URL(string: UIApplication.openSettingsURLString) {
          openURL(url)
        }
      } label: {
        HStack {
          Text(L10n.Settings.Language.AppLanguage.title)
          Spacer()
          Text(appLanguageDisplayName)
            .foregroundStyle(.secondary)
          Image(systemName: "arrow.up.right")
            .font(.footnote)
            .foregroundStyle(.tertiary)
            .accessibilityHidden(true)
        }
      }
      .foregroundStyle(.primary)
      .accessibilityHint(L10n.Settings.Language.AppLanguage.accessibilityHint)
    } footer: {
      Text(L10n.Settings.Language.AppLanguage.footer)
    }
    .themedRowBackground(theme)
  }

  private var translationSection: some View {
    Section {
      NavigationLink(value: SettingsSubpage.translateIntoLanguage) {
        HStack {
          Text(L10n.Settings.Language.TranslateInto.header)
          Spacer()
          Text(translateIntoDetail)
            .foregroundStyle(.secondary)
        }
      }
      .disabled(usesSystemOverlay)

      Toggle(
        L10n.Settings.Language.DefaultTranslationApp.title,
        isOn: useDefaultTranslationApp
      )
    } footer: {
      Text(
        usesSystemOverlay
          ? L10n.Settings.Language.DefaultTranslationApp.footer
          : L10n.Settings.Language.TranslateInto.footer
      )
    }
    .themedRowBackground(theme)
  }

  private var useDefaultTranslationApp: Binding<Bool> {
    Binding(
      get: { usesSystemOverlay },
      set: { isOn in
        storedUseDefaultTranslationApp = isOn
        if !isOn {
          replaceOverlaySentinelWithAppLanguage()
        }
      }
    )
  }

  private func replaceOverlaySentinelWithAppLanguage() {
    guard preference.isSystemOverlay else { return }
    translationTargetLanguage = TranslationTargetPreference.matchAppLanguage.rawValue
  }

  private var appLanguageDisplayName: String {
    let code = Bundle.main.preferredLocalizations.first ?? "en"
    return locale.localizedString(forLanguageCode: code) ?? code
  }

  private var translateIntoDetail: String {
    preference.detailLabel(appLocale: locale, displayLocale: locale)
  }
}
