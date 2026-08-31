import Foundation
@testable import MC1
import Testing

@Suite("RepeaterRegionEntry named parent")
struct RepeaterRegionEntryNamedParentTests {
  @Test
  func `namedParent is nil for Unscoped and for children of Unscoped`() {
    let unscoped = RepeaterRegionEntry(
      name: "*", parentName: nil, depth: 0, floodAllowed: true, isHome: false
    )
    let on = RepeaterRegionEntry(
      name: "on", parentName: "*", depth: 1, floodAllowed: true, isHome: false
    )
    #expect(unscoped.namedParent == nil)
    #expect(on.namedParent == nil)
  }

  @Test
  func `namedParent is the immediate named parent`() {
    let gta = RepeaterRegionEntry(
      name: "gta", parentName: "on", depth: 2, floodAllowed: true, isHome: false
    )
    let downtown = RepeaterRegionEntry(
      name: "downtown", parentName: "gta", depth: 3, floodAllowed: true, isHome: false
    )
    #expect(gta.namedParent == "on")
    #expect(downtown.namedParent == "gta")
  }
}

@Suite("RegionFloodToggleRow layout")
struct RegionFloodToggleRowLayoutTests {
  @Test
  func `visibleDepth is zero for the root`() {
    #expect(RegionFloodToggleRow.Layout.visibleDepth(regionDepth: 0, indentPerLevel: 12) == 0)
  }

  @Test
  func `visibleDepth caps at four when indent is the default`() {
    #expect(RegionFloodToggleRow.Layout.visibleDepth(regionDepth: 3, indentPerLevel: 12) == 3)
    #expect(RegionFloodToggleRow.Layout.visibleDepth(regionDepth: 5, indentPerLevel: 12) == 4)
  }

  @Test
  func `visibleDepth caps by gutter width at large type`() {
    #expect(RegionFloodToggleRow.Layout.visibleDepth(regionDepth: 5, indentPerLevel: 24) == 2)
    #expect(RegionFloodToggleRow.Layout.visibleDepth(regionDepth: 5, indentPerLevel: 48) == 1)
  }
}
