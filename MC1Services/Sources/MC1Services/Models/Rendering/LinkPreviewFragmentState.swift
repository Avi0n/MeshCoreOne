import Foundation

public struct LinkPreviewFragmentState: Sendable, Hashable {
    public enum Mode: Sendable, Hashable {
        case idle
        case loading(URL)
        case loaded(LinkPreviewDataDTO, image: ImageReference?, icon: ImageReference?)
        case noPreview
        case disabled(URL)
        case legacy(url: URL, title: String?, image: ImageReference?, icon: ImageReference?)
    }
    public let mode: Mode

    public init(mode: Mode) {
        self.mode = mode
    }
}
