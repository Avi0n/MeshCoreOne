import SwiftUI

/// Animated linear-gradient overlay driven by `TimelineView(.animation)` so
/// off-screen reused cells naturally pause. Static when Reduce Motion is on.
struct Shimmer: ViewModifier {
    let isActive: Bool

    private static let duration: Double = 1.5
    private static let highlightOpacity: Double = 0.35
    private static let frameInterval: Double = 1.0 / 60.0

    func body(content: Content) -> some View {
        if isActive {
            content
                .overlay {
                    TimelineView(.animation(minimumInterval: Self.frameInterval)) { context in
                        let elapsed = context.date.timeIntervalSinceReferenceDate
                        let progress = elapsed.truncatingRemainder(dividingBy: Self.duration) / Self.duration
                        let phase = CGFloat(progress) * 2 - 1
                        GeometryReader { proxy in
                            let width = proxy.size.width
                            LinearGradient(
                                stops: [
                                    .init(color: .clear, location: 0),
                                    .init(color: .white.opacity(Self.highlightOpacity), location: 0.5),
                                    .init(color: .clear, location: 1)
                                ],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                            .frame(width: width)
                            .offset(x: phase * width)
                        }
                    }
                }
                .clipped()
        } else {
            content
        }
    }
}
