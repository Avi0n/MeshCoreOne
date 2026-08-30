import MC1Services
import SwiftUI

struct IncomingAvatarGutter<Content: View>: View {
  let identity: IncomingAvatarIdentity?
  let reserveColumn: Bool
  let messageID: UUID
  @Environment(\.incomingAvatarFlight) private var flight
  @ViewBuilder var content: Content

  var body: some View {
    let isFlying = flight?.isFlying(messageID) == true
    let shouldReport = flight?.shouldReportFrame(
      messageID: messageID,
      hasIdentity: identity != nil
    ) == true

    Group {
      if let identity {
        HStack(alignment: .bottom, spacing: IncomingBubbleAvatarMetrics.gap) {
          ContactAvatar(
            name: identity.name,
            size: IncomingBubbleAvatarMetrics.size,
            imageData: IncomingAvatarJPEGStore.data(for: identity.matchedContactID)
          )
          .opacity(isFlying ? 0 : 1)
          .accessibilityHidden(true)
          .onGeometryChange(for: CGRect.self) { proxy in
            shouldReport ? proxy.frame(in: .global) : .null
          } action: { global in
            guard shouldReport, global != .null else { return }
            flight?.reportFrame(global, for: messageID)
          }
          content
        }
        .contentShape(.rect)
      } else if reserveColumn {
        content
          .padding(.leading, IncomingBubbleAvatarMetrics.columnWidth)
          .background(alignment: .bottomLeading) {
            if shouldReport {
              Color.clear
                .frame(
                  width: IncomingBubbleAvatarMetrics.size,
                  height: IncomingBubbleAvatarMetrics.size
                )
                .onGeometryChange(for: CGRect.self) { proxy in
                  proxy.frame(in: .global)
                } action: { global in
                  flight?.reportFrame(global, for: messageID)
                }
            }
          }
      } else {
        content
      }
    }
    .onDisappear { flight?.unbind(messageID) }
    .onChange(of: messageID) { oldID, _ in
      flight?.unbind(oldID)
    }
    .onChange(of: identity) { _, newIdentity in
      guard newIdentity == nil, flight?.isFlying(messageID) != true else { return }
      flight?.unbind(messageID)
    }
  }
}
