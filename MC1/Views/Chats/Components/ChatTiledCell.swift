import MessagingUI
import SwiftUI

/// Adapts an existing chat bubble view into a `MessagingUI` cell.
///
/// The bubble content is produced through `CellContentHost` rather than captured
/// directly: `TiledView` freezes its cell builder at creation, so reading the host
/// on every (re)configure is what lets recycled/reconfigured cells pick up the
/// current theme, link router, and action callbacks.
struct ChatTiledCell<Item: Identifiable, Content: View>: TiledCellContent {
  let item: Item
  let host: CellContentHost<Item, Content>

  func body(context: CellContext<Void>) -> some View {
    host.content?(item)
  }
}

/// Stable reference the cell builder closes over so `ChatTiledView` can swap in a
/// fresh content closure each render without recreating the collection view.
/// Only ever touched on the main thread (SwiftUI body and the library's main-thread
/// cell callbacks), so it needs no isolation or `Sendable` conformance.
final class CellContentHost<Item, Content: View> {
  var content: ((Item) -> Content)?
}
