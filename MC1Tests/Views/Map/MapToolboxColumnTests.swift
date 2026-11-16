import CoreGraphics
@testable import MC1
import SwiftUI
import Testing

@Suite("Map toolbox column")
struct MapToolboxColumnTests {
  @Test
  func `beside a visible vertical bar the toolbox shares its bottom`() {
    let shares = MapToolboxColumn.sharesTabBarBottom(
      followsVerticalBar: false,
      pinsToBottom: true,
      verticalBarEdge: .trailing,
      edgeInset: 72
    )
    #expect(shares)
    #expect(
      MapToolboxColumn.alignmentEdge(
        columnEdge: nil,
        sharesTabBarBottom: shares,
        verticalBarEdge: .trailing
      ) == .trailing
    )
  }

  @Test
  func `a leading bar keeps the toolbox on that side without taking the column`() {
    let shares = MapToolboxColumn.sharesTabBarBottom(
      followsVerticalBar: false,
      pinsToBottom: true,
      verticalBarEdge: .leading,
      edgeInset: 72
    )
    #expect(shares)
    #expect(MapToolboxColumn.safeAreaEdge(nil) == nil)
    #expect(
      MapToolboxColumn.alignmentEdge(
        columnEdge: nil,
        sharesTabBarBottom: true,
        verticalBarEdge: .leading
      ) == .leading
    )
  }

  @Test(arguments: [
    (true, true, HorizontalEdge?.some(.trailing), CGFloat(72)),
    (false, false, HorizontalEdge?.some(.trailing), CGFloat(72)),
    (false, true, HorizontalEdge?.none, CGFloat(72)),
    (false, true, HorizontalEdge?.some(.trailing), CGFloat(0)),
  ])
  func `other placements keep the safe-area bottom`(
    followsVerticalBar: Bool,
    pinsToBottom: Bool,
    verticalBarEdge: HorizontalEdge?,
    edgeInset: CGFloat
  ) {
    #expect(
      !MapToolboxColumn.sharesTabBarBottom(
        followsVerticalBar: followsVerticalBar,
        pinsToBottom: pinsToBottom,
        verticalBarEdge: verticalBarEdge,
        edgeInset: edgeInset
      )
    )
  }

  @Test
  func `following the bar still uses its column`() {
    #expect(
      MapToolboxColumn.alignmentEdge(
        columnEdge: .trailing,
        sharesTabBarBottom: false,
        verticalBarEdge: .trailing
      ) == .trailing
    )
    #expect(MapToolboxColumn.hidesTabBar(.trailing))
    #expect(MapToolboxColumn.safeAreaEdge(.trailing) == .trailing)
  }

  @Test
  func `sharing pulls back only the pad that faces the bar`() {
    let trailing = MapToolboxColumn.barFacingInset(sharesTabBarBottom: true, edge: .trailing)
    #expect(trailing.trailing == -MapToolboxColumn.toolbarGlassPadding)
    #expect(trailing.leading == 0)
    #expect(trailing.top == 0)
    #expect(trailing.bottom == 0)

    let leading = MapToolboxColumn.barFacingInset(sharesTabBarBottom: true, edge: .leading)
    #expect(leading.leading == -MapToolboxColumn.toolbarGlassPadding)
    #expect(leading.trailing == 0)

    #expect(MapToolboxColumn.barFacingInset(sharesTabBarBottom: false, edge: .trailing) == EdgeInsets())
  }
}
