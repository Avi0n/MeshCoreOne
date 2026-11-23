import MC1Services

extension PayloadType {
  /// SF Symbol shown beside the packet type in the RX Log.
  var systemImage: String {
    switch self {
    case .request: "arrow.up.circle"
    case .response: "arrow.down.circle"
    case .textMessage: "envelope"
    case .ack: "checkmark.circle"
    case .advert: "dot.radiowaves.left.and.right"
    case .groupText: "bubble.left.and.bubble.right"
    case .groupData: "square.stack.3d.up"
    case .anonRequest: "person.crop.circle.badge.questionmark"
    case .path: "point.topleft.down.to.point.bottomright.curvepath"
    case .trace: "point.3.connected.trianglepath.dotted"
    case .multipart: "square.split.2x2"
    case .control: "slider.horizontal.3"
    case .rawCustom: "curlybraces"
    case .unknown: "questionmark.circle"
    }
  }
}
