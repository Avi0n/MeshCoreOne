import Foundation
import SwiftData

/// Cached link preview metadata, keyed by URL for cross-message deduplication.
/// Multiple messages with the same URL reference a single LinkPreviewData row.
@Model
public final class LinkPreviewData {
    /// The URL this preview is for (unique key)
    @Attribute(.unique)
    public var url: String

    /// Title from link metadata
    public var title: String?

    /// Preview image data (hero image)
    @Attribute(.externalStorage)
    public var imageData: Data?

    /// Icon/favicon data
    @Attribute(.externalStorage)
    public var iconData: Data?

    /// Hero image pixel width, recorded at fetch time
    public var imageWidth: Int?

    /// Hero image pixel height, recorded at fetch time
    public var imageHeight: Int?

    /// When this preview was fetched
    public var fetchedAt: Date

    public init(
        url: String,
        title: String? = nil,
        imageData: Data? = nil,
        iconData: Data? = nil,
        imageWidth: Int? = nil,
        imageHeight: Int? = nil,
        fetchedAt: Date = Date()
    ) {
        self.url = url
        self.title = title
        self.imageData = imageData
        self.iconData = iconData
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
        self.fetchedAt = fetchedAt
    }
}
