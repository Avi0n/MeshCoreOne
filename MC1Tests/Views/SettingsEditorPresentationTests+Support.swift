import Foundation
@testable import MC1
import Testing

extension SettingsEditorPresentationTests {
  enum Exit: CaseIterable {
    case rootRemoved
    case rootReplaced
    case tabLeft
    case radioRemoved
    case radioReplaced

    @MainActor
    func apply(to context: Context) {
      switch self {
      case .rootRemoved: context.navigation.selectedSetting = nil
      case .rootReplaced: context.navigation.navigateToSetting(.maps)
      case .tabLeft: context.navigation.selectedTab = AppTab.chats.rawValue
      case .radioRemoved: context.radioID = nil
      case .radioReplaced: context.radioID = UUID()
      }
    }
  }

  @MainActor
  final class Context {
    let navigation = NavigationCoordinator()
    var descendants: [SettingsSubpage] = []
    var radioID: UUID? = UUID()
    var supportsRegions = true
    var knownRegions: [String] = []
    var submitted: [String] = []

    init(kind: SettingsEditorPresentation.Kind) {
      switch kind {
      case .deviceName:
        navigation.navigateToSetting(.deviceInfo)
      case .regionAdd:
        navigation.navigateToSetting(.advanced)
        descendants = [.manageRegions]
      }
    }

    func scope() -> SettingsEditorPresentation.Scope {
      SettingsEditorPresentation.Scope(
        selectedTab: navigation.selectedTab,
        root: navigation.selectedSetting,
        descendants: descendants,
        radioID: radioID,
        supportsRegions: supportsRegions,
        rootNavigationGeneration: navigation.settingsRootNavigationGeneration
      )
    }
  }

  @MainActor
  struct Fixture {
    let presentation = SettingsEditorPresentation()
    let context: Context

    init(kind: SettingsEditorPresentation.Kind) {
      context = Context(kind: kind)
    }

    func present(
      _ kind: SettingsEditorPresentation.Kind,
      sourceLocation: SourceLocation = #_sourceLocation
    ) throws -> SettingsEditorPresentation.Request {
      let context = context
      switch kind {
      case .deviceName:
        presentation.presentDeviceName(
          "Original",
          radioID: context.radioID,
          scope: { context.scope() },
          onSubmit: { context.submitted.append($0) }
        )
      case .regionAdd:
        presentation.presentRegionAdd(
          radioID: context.radioID,
          rootNavigationGeneration: context.navigation.settingsRootNavigationGeneration,
          scope: { context.scope() },
          knownRegions: { context.knownRegions },
          onSubmit: { context.submitted.append($0) }
        )
      }
      return try #require(presentation.request, sourceLocation: sourceLocation)
    }
  }

  @MainActor
  final class CallbackOwner {
    let context: Context
    var regions = ["North"]
    var submitted: [String] = []

    init(context: Context) {
      self.context = context
    }
  }
}
