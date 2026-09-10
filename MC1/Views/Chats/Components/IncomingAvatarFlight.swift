import MC1Services
import SwiftUI

/// Owns the in-flight cluster-end avatar. Gutters read flying IDs only; the
/// overlay reads frames so scroll-time `reportFrame` does not invalidate rows.
@Observable
@MainActor
final class IncomingAvatarFlight {
  struct Flight: Equatable {
    let fromID: UUID
    let toID: UUID
    let identity: IncomingAvatarIdentity
    let initialFromFrame: CGRect
  }

  /// Skip the drop when the conversation is scrolled up. Default matches a
  /// freshly opened thread sitting at the bottom.
  var isAtBottom = true

  /// Skip the drop under Reduce Motion; the in-cell avatar swaps instantly.
  var reduceMotion = false

  #if DEBUG
    /// Hosted tests set this false so animation completion does not clear
    /// `flight` before VoiceOver inspection. Production always completes.
    var completeOverlayOnFinish = true
  #endif

  private(set) var flight: Flight?
  private(set) var flyingFromID: UUID?
  private(set) var flyingToID: UUID?
  private var frames: [UUID: CGRect] = [:]

  func isFlying(_ id: UUID) -> Bool {
    id == flyingFromID || id == flyingToID
  }

  func shouldReportFrame(messageID: UUID, hasIdentity: Bool) -> Bool {
    if flyingFromID != nil {
      return messageID == flyingFromID || messageID == flyingToID
    }
    return hasIdentity
  }

  func frame(for id: UUID) -> CGRect? {
    frames[id]
  }

  func drawnFrame(progress: CGFloat) -> CGRect? {
    guard let flight else { return nil }
    let from = frames[flight.fromID] ?? flight.initialFromFrame
    let to = frames[flight.toID] ?? from
    return Self.lerp(from: from, to: to, progress: progress)
  }

  func beginFlight(from: UUID, to: UUID, identity: IncomingAvatarIdentity) {
    mutateWithoutAnimation {
      if flight != nil {
        clearFlight()
        return
      }
      guard isAtBottom, !reduceMotion else { return }
      guard let fromFrame = frames[from] else { return }
      flyingFromID = from
      flyingToID = to
      flight = Flight(
        fromID: from,
        toID: to,
        identity: identity,
        initialFromFrame: fromFrame
      )
    }
  }

  func reportFrame(_ frame: CGRect, for id: UUID) {
    mutateWithoutAnimation {
      frames[id] = frame
    }
  }

  func unbind(_ id: UUID) {
    mutateWithoutAnimation {
      frames.removeValue(forKey: id)
    }
  }

  func complete() {
    mutateWithoutAnimation {
      clearFlight()
    }
  }

  func overlay() -> some View {
    Overlay(controller: self)
  }

  static func lerp(from: CGRect, to: CGRect, progress: CGFloat) -> CGRect {
    let p = min(max(progress, 0), 1)
    return CGRect(
      x: from.origin.x + (to.origin.x - from.origin.x) * p,
      y: from.origin.y + (to.origin.y - from.origin.y) * p,
      width: from.size.width + (to.size.width - from.size.width) * p,
      height: from.size.height + (to.size.height - from.size.height) * p
    )
  }

  private func clearFlight() {
    if let from = flyingFromID {
      frames.removeValue(forKey: from)
    }
    flyingFromID = nil
    flyingToID = nil
    flight = nil
  }

  private func mutateWithoutAnimation(_ body: () -> Void) {
    var transaction = Transaction()
    transaction.animation = nil
    withTransaction(transaction, body)
  }
}

extension EnvironmentValues {
  @Entry var incomingAvatarFlight: IncomingAvatarFlight?
}

extension IncomingAvatarFlight {
  private struct Overlay: View {
    var controller: IncomingAvatarFlight
    @State private var progress: CGFloat = 0

    var body: some View {
      Group {
        if let flight = controller.flight {
          GeometryReader { geo in
            let overlayGlobal = geo.frame(in: .global)
            if let drawn = controller.drawnFrame(progress: progress) {
              let local = drawn.offsetBy(dx: -overlayGlobal.minX, dy: -overlayGlobal.minY)
              ContactAvatar(
                name: flight.identity.name,
                size: IncomingBubbleAvatarMetrics.size,
                imageData: IncomingAvatarJPEGStore.data(for: flight.identity.matchedContactID)
              )
              .frame(
                width: IncomingBubbleAvatarMetrics.size,
                height: IncomingBubbleAvatarMetrics.size
              )
              .offset(x: local.minX, y: local.minY)
              .accessibilityHidden(true)
              .allowsHitTesting(false)
            }
          }
        }
      }
      .allowsHitTesting(false)
      .accessibilityElement(children: .ignore)
      .accessibilityHidden(true)
      .onChange(of: controller.flight?.toID, initial: true) { _, toID in
        var reset = Transaction()
        reset.animation = nil
        withTransaction(reset) { progress = 0 }
        guard let toID else { return }
        withAnimation(.smooth(duration: IncomingBubbleAvatarMetrics.flightDuration)) {
          progress = 1
        } completion: {
          #if DEBUG
            guard controller.completeOverlayOnFinish else { return }
          #endif
          guard controller.flight?.toID == toID else { return }
          controller.complete()
        }
      }
    }
  }
}
