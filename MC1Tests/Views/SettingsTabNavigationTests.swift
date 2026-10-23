@testable import MC1
import Testing

@Suite("Settings tab navigation", .serialized)
@MainActor
struct SettingsTabNavigationTests {
  @Test(arguments: SettingsDestination.allCases)
  func `Compact back returns through the selected page`(_ destination: SettingsDestination) async throws {
    let harness = try mounted(destination.root)
    defer { harness.tearDown() }
    try await requireChild(destination, on: harness)
    #expect(try await harness.waitUntil { harness.tabControlsAreOnScreen() }, "\(harness.describe())")
    try #require(try await harness.pop(), "\(harness.describe())")
    try #require(try await harness.waitUntil { showsRoot(destination.root, on: harness) && childTitleIsHidden(destination, on: harness) }, "\(harness.describe())")
    #expect(try await harness.waitUntil { harness.tabControlsAreOnScreen() }, "\(harness.describe())")
    try #require(try await harness.pop(), "\(harness.describe())")
    try #require(try await harness.waitUntil { harness.showsSettingsList() }, "\(harness.describe())")
    #expect(try await harness.waitUntil { harness.tabControlsAreOnScreen() }, "\(harness.describe())")
  }

  @Test
  func `Regular back keeps the selected detail`() async throws {
    let destination = SettingsDestination.manageRegions
    let harness = try mounted(destination.root)
    defer { harness.tearDown() }
    try await requireChild(destination, on: harness)
    try #require(try await harness.resize(regularWidth: true), "\(harness.describe())")
    try #require(try await harness.waitUntil { showsChild(destination, on: harness, searching: false) }, "\(harness.describe())")
    try #require(try await harness.pop(), "\(harness.describe())")
    try #require(
      try await harness.waitUntil {
        showsRoot(destination.root, on: harness)
          && childTitleIsHidden(destination, on: harness)
          && harness.showsNavigationTitle(L10n.Settings.title)
      },
      "\(harness.describe())"
    )
  }

  @Test(arguments: [SettingsDestination.translateInto, .manageRegions])
  func `Tab switch keeps the descendant`(_ destination: SettingsDestination) async throws {
    let harness = try mounted(destination.root)
    defer { harness.tearDown() }
    try await requireChild(destination, on: harness)
    try await captureSearch(destination, on: harness)
    harness.selectTab(.nodes)
    try #require(try await harness.waitUntil { harness.showsSelectedTab(.nodes) }, "\(harness.describe())")
    harness.selectTab(.settings)
    try #require(
      try await harness.waitUntil {
        harness.showsSelectedTab(.settings) && showsChild(destination, on: harness, searching: destination.preservesSearch)
      },
      "\(harness.describe())"
    )
    try await requireSearchPreserved(destination, on: harness)
  }

  @Test(arguments: [SettingsDestination.translateInto, .manageRegions])
  func `Programmatic navigation replaces descendants`(_ destination: SettingsDestination) async throws {
    let harness = try mounted(destination.root)
    defer { harness.tearDown() }
    try await requireChild(destination, on: harness)
    harness.navigation.navigateToSetting(destination.root.detail)
    try #require(
      try await harness.waitUntil { showsRoot(destination.root, on: harness) && childTitleIsHidden(destination, on: harness) },
      "\(harness.describe())"
    )

    try #require(try await harness.openChild(row: destination.row, title: destination.title, markers: destination.markers, detail: destination.root.detail), "\(harness.describe())")
    harness.selectTab(.chats)
    try #require(try await harness.waitUntil { harness.showsSelectedTab(.chats) }, "\(harness.describe())")
    harness.navigation.navigateToSetting(.backup)
    try #require(
      try await harness.waitUntil {
        harness.showsSelectedTab(.settings)
          && showsBackup(on: harness)
          && childTitleIsHidden(destination, on: harness)
      },
      "\(harness.describe())"
    )
  }

  @Test(arguments: [SettingsDestination.translateInto, .manageRegions])
  func `Presentation changes keep the descendant`(_ destination: SettingsDestination) async throws {
    let harness = try mounted(destination.root)
    defer { harness.tearDown() }
    try await requireChild(destination, on: harness)
    try await captureSearch(destination, on: harness)
    harness.note = "resize to regular"
    try #require(try await harness.resize(regularWidth: true), "\(harness.describe())")
    try #require(try await harness.waitUntil { showsChild(destination, on: harness, searching: destination.preservesSearch) }, "\(harness.describe())")
    try await requireSearchPreserved(destination, on: harness)
    harness.note = "resize to compact height"
    try #require(try await harness.resize(regularWidth: false, compactHeight: true), "\(harness.describe())")
    try #require(try await harness.waitUntil { showsChild(destination, on: harness, searching: destination.preservesSearch) }, "\(harness.describe())")
    try await requireSearchPreserved(destination, on: harness)
    harness.note = "resize to compact"
    try #require(try await harness.resize(regularWidth: false), "\(harness.describe())")
    try #require(try await harness.waitUntil { showsChild(destination, on: harness, searching: destination.preservesSearch) }, "\(harness.describe())")
    try await requireSearchPreserved(destination, on: harness)
    try #require(try await harness.pop(), "\(harness.describe())")
    try #require(
      try await harness.waitUntil { showsRoot(destination.root, on: harness) && childTitleIsHidden(destination, on: harness) },
      "\(harness.describe())"
    )
    try #require(try await harness.pop(), "\(harness.describe())")
    try #require(try await harness.waitUntil { harness.showsSettingsList() }, "\(harness.describe())")
  }

  @Test
  func `clear per radio selection clears device page while settings is active`() async throws {
    let harness = try SettingsTabHarness.make(connected: true)
    defer { harness.tearDown() }
    try await requireChild(.manageRegions, on: harness)
    harness.navigation.clearPerRadioSelection()
    try #require(try await harness.waitUntil { harness.showsSettingsList() }, "\(harness.describe())")
  }

  @Test
  func `clear per radio selection clears device page while settings is inactive`() async throws {
    let harness = try SettingsTabHarness.make(connected: true)
    defer { harness.tearDown() }
    try await requireChild(.manageRegions, on: harness)
    harness.selectTab(.chats)
    harness.navigation.clearPerRadioSelection()
    try await leaveAndReturn(harness)
    try #require(try await harness.waitUntil { harness.showsSettingsList() && childTitleIsHidden(.manageRegions, on: harness) }, "\(harness.describe())")
  }

  @Test
  func `disconnect keeps app wide descendant while settings is inactive`() async throws {
    let harness = try SettingsTabHarness.make(connected: true)
    defer { harness.tearDown() }
    try await requireChild(.translateInto, on: harness)
    try await captureSearch(.translateInto, on: harness)
    harness.selectTab(.chats)
    harness.disconnect()
    try await leaveAndReturn(harness)
    try #require(try await harness.waitUntil { showsChild(.translateInto, on: harness, searching: true) }, "\(harness.describe())")
    try await requireSearchPreserved(.translateInto, on: harness)
  }

  @Test
  func `device snapshot replacement keeps device descendant`() async throws {
    let harness = try SettingsTabHarness.make(connected: true)
    defer { harness.tearDown() }
    try await requireChild(.manageRegions, on: harness)
    harness.replaceConnectedDevice()
    try #require(
      try await harness.waitUntil {
        harness.appState.connectedDevice?.nodeName == "Field" && showsChild(.manageRegions, on: harness, searching: false)
      },
      "\(harness.describe())"
    )
  }

  @Test
  func `compact settings list keeps tab controls`() async throws {
    let harness = try SettingsTabHarness.make(connected: false)
    defer { harness.tearDown() }
    try #require(try await harness.showSettingsList(), "\(harness.describe())")
    #expect(try await harness.waitUntil { harness.tabControlsAreOnScreen() }, "\(harness.describe())")
  }

  @Test(arguments: SettingsRoot.allCases)
  func `Compact root keeps tab controls on screen`(_ root: SettingsRoot) async throws {
    let harness = try mounted(root)
    defer { harness.tearDown() }
    try await requireRoot(root, on: harness)
    #expect(try await harness.waitUntil { harness.tabControlsAreOnScreen() }, "\(harness.describe())")
  }
}
