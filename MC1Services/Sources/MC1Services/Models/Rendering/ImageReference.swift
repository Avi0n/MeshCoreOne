import Foundation

/// Type-erased handle to a UIImage that lives on the view model. Hashable via
/// cache key + role discriminator; equality is structural. `Sendable` because
/// it only carries a Hashable key. The bubble resolves the actual UIImage via
/// an `imageResolver: (ImageReference) -> UIImage?` callback at render time.
public struct ImageReference: Sendable, Hashable {
    public let cacheKey: UUID
    public let role: Role
    public enum Role: Sendable, Hashable {
        case inline, linkPreviewImage, linkPreviewIcon
    }

    public init(cacheKey: UUID, role: Role) {
        self.cacheKey = cacheKey
        self.role = role
    }
}
