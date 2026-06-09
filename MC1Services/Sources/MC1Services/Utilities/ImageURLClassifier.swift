import Foundation

/// URL-level classifier for image hosting. Pure string/URL inspection — no
/// image decoding, no UIKit/ImageIO dependency. Lives in MC1Services so the
/// chat fragment builder can be SwiftUI-free.
///
/// The companion `ImageURLDetector` in MC1 owns image *decoding* (depends on
/// UIKit/ImageIO) and forwards URL classification to this type.
public enum ImageURLClassifier {

    private static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "png", "gif", "webp", "heic"
    ]

    /// Returns `true` if the URL's path extension is a known image type.
    public static func isDirectImageURL(_ url: URL) -> Bool {
        imageExtensions.contains(url.pathExtension.lowercased())
    }

    /// Returns the direct image URL for known hosting page URLs, or `nil` if
    /// not resolvable.
    public static func resolveImageURL(_ url: URL) -> URL? {
        guard let host = url.host()?.lowercased() else { return nil }

        if host == "giphy.com" || host == "www.giphy.com" {
            return resolveGiphyURL(url)
        }

        // Already a direct Giphy media URL — no resolution needed.
        if host == "media.giphy.com" || host == "i.giphy.com" {
            return nil
        }

        return nil
    }

    /// Returns `true` if the URL points to a direct image or a resolvable
    /// hosting page.
    public static func isImageURL(_ url: URL) -> Bool {
        isDirectImageURL(url) || resolveImageURL(url) != nil
    }

    /// Returns the direct image URL: the URL itself for direct images, or
    /// the resolved URL for hosting pages.
    public static func directImageURL(for url: URL) -> URL {
        if isDirectImageURL(url) { return url }
        return resolveImageURL(url) ?? url
    }

    private static func resolveGiphyURL(_ url: URL) -> URL? {
        let pathComponents = url.pathComponents

        guard pathComponents.count >= 3 else { return nil }

        let section = pathComponents[1].lowercased()
        guard section == "gifs" || section == "embed" else { return nil }

        let lastComponent = pathComponents[2]

        let giphyID: String
        if section == "gifs" {
            giphyID = lastComponent.components(separatedBy: "-").last ?? lastComponent
        } else {
            giphyID = lastComponent
        }

        guard !giphyID.isEmpty else { return nil }

        return URL(string: "https://i.giphy.com/media/\(giphyID)/giphy.gif")
    }
}
