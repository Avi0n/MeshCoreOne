import MC1Services
import SwiftUI

struct ActionsDetailsSection: View {
  let message: MessageDTO
  let availability: MessageActionAvailability
  @Binding var isDetailExpanded: Bool
  let repeats: [MessageRepeatDTO]?
  let pathViewModel: MessagePathViewModel

  private var arrivals: [MessagePathArrival] {
    MessagePathArrivals.assemble(message: message, repeats: repeats ?? [])
  }

  private var incomingArrivalCount: Int {
    if repeats == nil {
      max(arrivals.count, MessagePathArrivals.arrivalCount(for: message))
    } else {
      arrivals.count
    }
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      if availability.showsPathDetail {
        MessagePathDetailBlock(
          message: message,
          arrivals: arrivals,
          pathViewModel: pathViewModel,
          isDetailExpanded: $isDetailExpanded
        )
      }

      Text(L10n.Chats.Chats.Message.Action.details)
        .font(.caption)
        .foregroundStyle(.secondary)
        .padding(.horizontal)
        .padding(.top, 12)
        .padding(.bottom, 4)

      if message.isOutgoing {
        ActionsOutgoingDetailsRows(message: message)
      } else {
        ActionsIncomingDetailsRows(
          message: message,
          arrivalCount: incomingArrivalCount
        )
      }
    }
  }
}

private struct ActionsOutgoingDetailsRows: View {
  let message: MessageDTO

  var body: some View {
    ActionInfoRow(text: L10n.Chats.Chats.Message.Info.sent(
      message.senderDate.formatted(date: .abbreviated, time: .standard)
    ))

    if let rtt = message.roundTripTime {
      ActionInfoRow(text: L10n.Chats.Chats.Message.Info.roundTrip(Int(rtt)))
    }

    if message.heardRepeats > 0 {
      let word = message.heardRepeats == 1
        ? L10n.Chats.Chats.Message.Repeat.singular
        : L10n.Chats.Chats.Message.Repeat.plural
      ActionInfoRow(text: L10n.Chats.Chats.Message.Info.heardRepeats(message.heardRepeats, word))
    }
  }
}

private struct ActionsIncomingDetailsRows: View {
  let message: MessageDTO
  let arrivalCount: Int

  var body: some View {
    let hopsText = L10n.Chats.Chats.Message.Info.hops(hopCountFormatted(message))
    ActionInfoRow(
      text: arrivalCount > 1
        ? "\(hopsText) · \(L10n.Chats.Chats.Path.Arrival.first)"
        : hopsText,
      icon: "arrowshape.bounce.right"
    )

    if let hashSize = message.pathHashSizeIfKnown {
      ActionInfoRow(text: L10n.Chats.Chats.Message.Info.pathHash(hashSize))
    }

    if message.routeType == .tcFlood {
      let regionText: String = {
        switch RegionScopeSemantics.coalesce(
          scope: message.regionScope,
          matches: message.regionScopeMatches
        ) {
        case .none:
          return L10n.Chats.Chats.Message.Info.regionUnresolved
        case let .unique(name):
          return L10n.Chats.Chats.Message.Info.floodedUnder(name)
        case let .ambiguous(names):
          let list = ListFormatter.localizedString(byJoining: names)
          return L10n.Chats.Chats.Message.Info.regionAmbiguous(list)
        }
      }()
      ActionInfoRow(text: regionText, icon: "globe")
    }

    let sentText = L10n.Chats.Chats.Message.Info.sent(
      message.senderDate.formatted(date: .abbreviated, time: .standard)
    )
    let adjusted = message.timestampCorrected ? " " + L10n.Chats.Chats.Message.Info.adjusted : ""
    ActionInfoRow(text: sentText + adjusted)

    if message.timestampCorrected {
      ActionInfoRow(text: L10n.Chats.Chats.Message.Info.originalSendTime(
        message.wireSentDate.formatted(date: .abbreviated, time: .standard)
      ))
    }

    ActionInfoRow(text: L10n.Chats.Chats.Message.Info.received(
      message.createdAt.formatted(date: .abbreviated, time: .standard)
    ))

    if let snr = message.snr {
      let snrText = L10n.Chats.Chats.Message.Info.snr(snrFormatted(snr))
      ActionInfoRow(
        text: arrivalCount > 1
          ? "\(snrText) · \(L10n.Chats.Chats.Path.Arrival.first)"
          : snrText
      )
    }
  }

  private func snrFormatted(_ snr: Double) -> String {
    let quality = SNRQuality(snr: snr).localizedLabel
    return "\(snr.formatted(.number.precision(.fractionLength(1)))) dB (\(quality))"
  }

  private func hopCountFormatted(_ message: MessageDTO) -> String {
    if message.isDirectRouted {
      return L10n.Chats.Chats.Message.Hops.direct
    }
    return "\(message.hopCount)"
  }
}
