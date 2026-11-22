enum PixelPaintTool: CaseIterable, Hashable {
  case brush
  case eraser
  case fill

  var title: String {
    switch self {
    case .brush: L10n.Chats.Chats.Mcoimg.Tool.brush
    case .eraser: L10n.Chats.Chats.Mcoimg.Tool.eraser
    case .fill: L10n.Chats.Chats.Mcoimg.Tool.fill
    }
  }

  var systemImage: String {
    switch self {
    case .brush: "paintbrush.pointed"
    case .eraser: "eraser"
    case .fill: "drop.fill"
    }
  }
}
