import Foundation
import SwiftData

/// Cached link preview metadata, keyed by URL for cross-message deduplication.
/// Multiple messages with the same URL reference a single LinkPreviewData row.
@Model
final class LinkPreviewData {
    /// The URL this preview is for (unique key)
    @Attribute(.unique)
    var url: String

    /// Title from link metadata
    var title: String?

    /// Preview image data (hero image)
    @Attribute(.externalStorage)
    var imageData: Data?

    /// Icon/favicon data
    @Attribute(.externalStorage)
    var iconData: Data?

    /// Hero image pixel width, recorded at fetch time
    var imageWidth: Int?

    /// Hero image pixel height, recorded at fetch time
    var imageHeight: Int?

    /// When this preview was fetched
    var fetchedAt: Date

    init(
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
