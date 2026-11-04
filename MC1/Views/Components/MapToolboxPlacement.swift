import SwiftUI

enum MapToolboxColumn {
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
}

/// Bottom-corner toolbox, unless `pinsToBottom` is false. `followsVerticalBar`
/// moves it into a vertical-bar column and hides the tab bar; false leaves both visible.
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
        verticalBarEdge: nil,
        bottomPadding: bottomPadding,
        pinsToBottom: pinsToBottom,
        content: content
      )
    }
  }

  @available(iOS 27.1, *)
  private struct VerticalBarToolbox<Toolbox: View>: View {
    @Environment(\.toolbarVerticalEdge) private var toolbarVerticalEdge

    var bottomPadding: CGFloat
    var pinsToBottom: Bool
    var followsVerticalBar: Bool
    @ViewBuilder var content: () -> Toolbox

    var body: some View {
      ToolboxStack(
        verticalBarEdge: followsVerticalBar ? toolbarVerticalEdge : nil,
        bottomPadding: bottomPadding,
        pinsToBottom: pinsToBottom,
        content: content
      )
    }
  }
}

private struct ToolboxStack<Content: View>: View {
  var verticalBarEdge: HorizontalEdge?
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
    .modifier(ToolboxColumnSafeArea(edge: verticalBarEdge))
    .padding(.bottom, pinsToBottom ? bottomPadding : 0)
    .modifier(VerticalBarTabBarHider(edge: verticalBarEdge))
  }

  private var row: some View {
    HStack {
      if !MapToolboxColumn.alignsLeading(verticalBarEdge) {
        Spacer()
      }
      content()
      if MapToolboxColumn.alignsLeading(verticalBarEdge) {
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
