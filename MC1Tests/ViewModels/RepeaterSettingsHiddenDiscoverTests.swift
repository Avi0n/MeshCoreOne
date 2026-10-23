import Foundation
@testable import MC1
import Testing

@Suite("RepeaterSettingsViewModel hidden from Discover")
@MainActor
struct RepeaterSettingsHiddenDiscoverTests {
  @Test
  func `footer condition is true only when both advert intervals are zero`() {
    let viewModel = RepeaterSettingsViewModel()

    viewModel.advertIntervalMinutes = 0
    viewModel.floodAdvertIntervalHours = 0
    #expect(viewModel.isHiddenFromDiscover)

    viewModel.floodAdvertIntervalHours = 1
    #expect(!viewModel.isHiddenFromDiscover)

    viewModel.advertIntervalMinutes = 60
    viewModel.floodAdvertIntervalHours = 0
    #expect(!viewModel.isHiddenFromDiscover)

    viewModel.advertIntervalMinutes = nil
    viewModel.floodAdvertIntervalHours = 0
    #expect(!viewModel.isHiddenFromDiscover)

    viewModel.advertIntervalMinutes = 0
    viewModel.floodAdvertIntervalHours = nil
    #expect(!viewModel.isHiddenFromDiscover)
  }
}
