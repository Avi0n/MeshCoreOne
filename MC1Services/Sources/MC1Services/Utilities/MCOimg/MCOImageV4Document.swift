public struct MCOImageV4Document: Sendable, Hashable {
  public let width: Int
  public let height: Int
  public let profile: Int
  public let backgroundColor: Int?
  public let figures: [MCOImageFigure]
  public let targetName: String?
  public let replyTimestamp: UInt32?
}
