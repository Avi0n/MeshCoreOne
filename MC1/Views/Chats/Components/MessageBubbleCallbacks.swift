import CoreLocation

/// Callbacks for message bubble interactions
struct MessageBubbleCallbacks {
    var onRetry: (() -> Void)?
    var onReaction: ((String) -> Void)?
    var onLongPress: (() -> Void)?
    var onImageTap: (() -> Void)?
    var onRetryImageFetch: (() -> Void)?
    var onRequestPreviewFetch: (() -> Void)?
    var onManualPreviewFetch: (() -> Void)?
    var onMapPreviewTap: ((CLLocationCoordinate2D) -> Void)?
}
