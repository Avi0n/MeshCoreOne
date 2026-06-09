import UIKit

/// `NSObject` proxy used as the `CADisplayLink` target so the link can
/// retain the proxy without retaining the controller. The proxy holds a
/// closure rather than a typed `weak` reference because
/// `ChatTableViewController` is generic and exposes no Objective-C
/// representable class type the proxy could store directly.
@MainActor
final class ChatScrollDisplayLinkProxy: NSObject {
    var onTick: (() -> Void)?

    @objc func tick(_ link: CADisplayLink) {
        onTick?()
    }
}
