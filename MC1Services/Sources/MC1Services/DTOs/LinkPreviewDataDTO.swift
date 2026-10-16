import Foundation

/// Data transfer object for link preview data
public struct LinkPreviewDataDTO: Identifiable, Sendable, Hashable {
  /// URL is the ID (unique key)
  public let id: String
  public let url: String
  public let title: String?
  public let imageData: Data?
  public let iconData: Data?
  public let imageWidth: Int?
  public let imageHeight: Int?
  public let fetchedAt: Date

  public init(
    url: String,
    title: String? = nil,
    imageData: Data? = nil,
    iconData: Data? = nil,
    imageWidth: Int? = nil,
    imageHeight: Int? = nil,
    fetchedAt: Date = Date()
  ) {
    id = url
    self.url = url
    self.title = title
    self.imageData = imageData
    self.iconData = iconData
    self.imageWidth = imageWidth
    self.imageHeight = imageHeight
    self.fetchedAt = fetchedAt
  }

  init(from model: LinkPreviewData) {
    id = model.url
    url = model.url
    title = model.title
    imageData = model.imageData
    iconData = model.iconData
    imageWidth = model.imageWidth
    imageHeight = model.imageHeight
    fetchedAt = model.fetchedAt
  }
}
