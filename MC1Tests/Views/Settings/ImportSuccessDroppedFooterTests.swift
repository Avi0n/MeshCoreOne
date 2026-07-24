@testable import MC1
@testable import MC1Services
import Testing

@MainActor
@Suite("ImportSuccessContent dropped footer")
struct ImportSuccessDroppedFooterTests {
  @Test
  func `Dropped footer blames discover cap, not channel slots, when only discover nodes drop`() {
    var result = ImportResult()
    result.record(.discoveredNodes, dropped: 5)
    let footer = ImportSuccessContent.droppedFooterText(for: result)
    #expect(
      footer == L10n.Settings.Settings.Backup.Import.Success.droppedFooterDiscoveredNodes(
        PersistenceStore.maxDiscoveredNodes
      )
    )
    #expect(!footer.localizedCaseInsensitiveContains("channel"))
  }

  @Test
  func `Dropped footer keeps channel copy when only channels drop`() {
    var result = ImportResult()
    result.record(.channels, dropped: 2)
    let footer = ImportSuccessContent.droppedFooterText(for: result)
    #expect(footer == L10n.Settings.Settings.Backup.Import.Success.droppedFooter)
  }

  @Test
  func `Dropped footer uses mixed copy when channels and discover nodes both drop`() {
    var result = ImportResult()
    result.record(.channels, dropped: 1)
    result.record(.discoveredNodes, dropped: 3)
    let footer = ImportSuccessContent.droppedFooterText(for: result)
    #expect(
      footer == L10n.Settings.Settings.Backup.Import.Success.droppedFooterMixed(
        PersistenceStore.maxDiscoveredNodes
      )
    )
  }
}
