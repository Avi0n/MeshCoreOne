import Foundation

public struct InlineImage: Sendable, Hashable {
    public enum LoadState: Sendable, Hashable {
        case idle(URL)
        case loading(URL)
        case loaded(ImageReference, isGIF: Bool)
        case failed(URL)
    }
    public let state: LoadState
    public let autoPlayGIFs: Bool

    public init(state: LoadState, autoPlayGIFs: Bool) {
        self.state = state
        self.autoPlayGIFs = autoPlayGIFs
    }
}
