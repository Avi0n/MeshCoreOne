import SwiftUI

enum MapToolboxColumn {
  /// The vertical tab bar capsule keeps this margin from the screen edge.
  /// The home-indicator inset is taller, so the safe-area bottom sits above it.
  static let verticalTabBarScreenMargin: CGFloat = 24

  /// Matches the pad `MapControlsToolbar` puts around the glass.
  static let toolbarGlassPadding: CGFloat = 16

  static var sharedTabBarBottomPadding: CGFloat {
    verticalTabBarScreenMargin - toolbarGlassPadding
  }

  /// The system's own inset stays between the capsules. Other sides keep their pad.
  static func barFacingInset(sharesTabBarBottom: Bool, edge: HorizontalEdge?) -> EdgeInsets {
    guard sharesTabBarBottom else { return EdgeInsets() }
    let pull = -toolbarGlassPadding
    switch edge {
    case .leading:
      return EdgeInsets(top: 0, leading: pull, bottom: 0, trailing: 0)
    case .trailing:
      return EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: pull)
    case nil:
      return EdgeInsets()
    }
  }

  static func alignsLeading(_ verticalBarEdge: HorizontalEdge?) -> Bool {
    verticalBarEdge == .leading
  }

  /// The vertical bar occupies this safe-area edge. `nil` keeps the toolbox inside the safe area.
  static func safeAreaEdge(_ verticalBarEdge: HorizontalEdge?) -> Edge.Set? {
    switch verticalBarEdge {
    case .leading:
      .leading
    case .trailing:
      .trailing
    case nil:
      nil
    }
  }

  static func hidesTabBar(_ verticalBarEdge: HorizontalEdge?) -> Bool {
    verticalBarEdge != nil
  }

  static func inset(on edge: HorizontalEdge?, of insets: EdgeInsets) -> CGFloat {
    switch edge {
    case .leading:
      insets.leading
    case .trailing:
      insets.trailing
    case nil:
      0
    }
  }

  /// Share the tab bar bottom only while the toolbox stays beside a visible bar.
  /// A preferred edge with no inset has no column.
  static func sharesTabBarBottom(
    followsVerticalBar: Bool,
    pinsToBottom: Bool,
    verticalBarEdge: HorizontalEdge?,
    edgeInset: CGFloat
  ) -> Bool {
    !followsVerticalBar
      && pinsToBottom
      && verticalBarEdge != nil
      && edgeInset > 0
  }

  /// Following the bar uses that column. Sharing keeps the safe area and sits on the bar's side.
  static func alignmentEdge(
    columnEdge: HorizontalEdge?,
    sharesTabBarBottom: Bool,
    verticalBarEdge: HorizontalEdge?
  ) -> HorizontalEdge? {
    if columnEdge != nil {
      return columnEdge
    }
    if sharesTabBarBottom {
      return verticalBarEdge
    }
    return nil
  }
}

/// Bottom-corner toolbox, unless `pinsToBottom` is false. `followsVerticalBar`
/// moves it into a vertical-bar column and hides the tab bar. Beside a visible bar, both stay visible and share its bottom without the facing pad.
struct MapToolboxPlacement<Content: View>: View {
  var bottomPadding: CGFloat = 0
  var pinsToBottom: Bool = true
  var followsVerticalBar: Bool = true
  @ViewBuilder var content: () -> Content

  var body: some View {
    if #available(iOS 27.1, *) {
      VerticalBarToolbox(
        bottomPadding: bottomPadding,
        pinsToBottom: pinsToBottom,
        followsVerticalBar: followsVerticalBar,
        content: content
      )
    } else {
      ToolboxStack(
        columnEdge: nil,
        alignmentEdge: nil,
        sharesTabBarBottom: false,
        bottomPadding: bottomPadding,
        pinsToBottom: pinsToBottom,
        content: content
      )
    }
  }

  @available(iOS 27.1, *)
  private struct VerticalBarToolbox<Toolbox: View>: View {
    @Environment(\.toolbarVerticalEdge) private var toolbarVerticalEdge
    @State private var edgeInset: CGFloat = 0

    var bottomPadding: CGFloat
    var pinsToBottom: Bool
    var followsVerticalBar: Bool
    @ViewBuilder var content: () -> Toolbox

    var body: some View {
      let barEdge = toolbarVerticalEdge
      let columnEdge: HorizontalEdge? = followsVerticalBar ? barEdge : nil
      let sharesTabBarBottom = MapToolboxColumn.sharesTabBarBottom(
        followsVerticalBar: followsVerticalBar,
        pinsToBottom: pinsToBottom,
        verticalBarEdge: barEdge,
        edgeInset: edgeInset
      )
      ToolboxStack(
        columnEdge: columnEdge,
        alignmentEdge: MapToolboxColumn.alignmentEdge(
          columnEdge: columnEdge,
          sharesTabBarBottom: sharesTabBarBottom,
          verticalBarEdge: barEdge
        ),
        sharesTabBarBottom: sharesTabBarBottom,
        bottomPadding: bottomPadding,
        pinsToBottom: pinsToBottom,
        content: content
      )
      .onGeometryChange(for: CGFloat.self) { proxy in
        MapToolboxColumn.inset(on: barEdge, of: proxy.safeAreaInsets)
      } action: { edgeInset = $0 }
    }
  }
}

private struct ToolboxStack<Content: View>: View {
  var columnEdge: HorizontalEdge?
  var alignmentEdge: HorizontalEdge?
  var sharesTabBarBottom: Bool
  var bottomPadding: CGFloat
  var pinsToBottom: Bool
  @ViewBuilder var content: () -> Content

  var body: some View {
    Group {
      if pinsToBottom {
        VStack {
          Spacer()
          row
        }
      } else {
        row
      }
    }
    .modifier(ToolboxColumnSafeArea(edge: columnEdge))
    .modifier(
      ToolboxBottomAlignment(
        sharesTabBarBottom: sharesTabBarBottom,
        extraPadding: pinsToBottom ? bottomPadding : 0
      )
    )
    .modifier(VerticalBarTabBarHider(edge: columnEdge))
  }

  private var row: some View {
    HStack {
      if !MapToolboxColumn.alignsLeading(alignmentEdge) {
        Spacer()
      }
      content()
        .padding(
          MapToolboxColumn.barFacingInset(
            sharesTabBarBottom: sharesTabBarBottom,
            edge: alignmentEdge
          )
        )
      if MapToolboxColumn.alignsLeading(alignmentEdge) {
        Spacer()
      }
    }
  }
}

private struct ToolboxColumnSafeArea: ViewModifier {
  var edge: HorizontalEdge?

  func body(content: Content) -> some View {
    if let edge = MapToolboxColumn.safeAreaEdge(edge) {
      content.ignoresSafeArea(.container, edges: edge)
    } else {
      content
    }
  }
}

private struct ToolboxBottomAlignment: ViewModifier {
  var sharesTabBarBottom: Bool
  var extraPadding: CGFloat

  func body(content: Content) -> some View {
    if sharesTabBarBottom {
      content
        .padding(.bottom, MapToolboxColumn.sharedTabBarBottomPadding + extraPadding)
        .ignoresSafeArea(.container, edges: .bottom)
    } else {
      content.padding(.bottom, extraPadding)
    }
  }
}

private struct VerticalBarTabBarHider: ViewModifier {
  var edge: HorizontalEdge?

  func body(content: Content) -> some View {
    if MapToolboxColumn.hidesTabBar(edge) {
      content.toolbarVisibility(.hidden, for: .tabBar)
    } else {
      content
    }
  }
}
