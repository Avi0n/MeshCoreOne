import SwiftUI

/// Horizontal capsule tabs for choosing among arrivals.
struct MessagePathArrivalCapsules: View {
  /// Opaque accent chips on the actions sheet; glass pills over the path map.
  enum Chrome {
    case plain
    case glass
  }

  private static let selectionAnimationDuration: TimeInterval = 0.2
  private static let pillSpacing: CGFloat = 8
  private static let pillHorizontalPadding: CGFloat = 12
  static let pillVerticalPadding: CGFloat = 8
  static let subtitleSpacing: CGFloat = 2
  private static let selectedSubtitleOpacity: Double = 0.7

  let arrivals: [MessagePathArrival]
  @Binding var selectedID: UUID?
  let reduceMotion: Bool
  var chrome: Chrome = .plain
  @Environment(\.appTheme) private var theme
  @Namespace private var glassNamespace

  var body: some View {
    ScrollView(.horizontal) {
      pillsRow
        .animation(
          reduceMotion ? nil : .easeInOut(duration: Self.selectionAnimationDuration),
          value: selectedID
        )
    }
    .scrollIndicators(.hidden)
    .scrollClipDisabled()
  }

  @ViewBuilder
  private var pillsRow: some View {
    if chrome == .glass {
      LiquidGlassContainer(spacing: Self.pillSpacing) {
        pillsStack
      }
    } else {
      pillsStack
    }
  }

  private var pillsStack: some View {
    HStack(spacing: Self.pillSpacing) {
      ForEach(arrivals) { arrival in
        capsuleButton(for: arrival)
      }
    }
  }

  @ViewBuilder
  private func capsuleButton(for arrival: MessagePathArrival) -> some View {
    let selected = isSelected(arrival)
    let accentFill = usesAccentFill
    let button = Button {
      selectedID = arrival.id
    } label: {
      VStack(alignment: .leading, spacing: Self.subtitleSpacing) {
        Text(title(for: arrival))
          .font(.caption.weight(.semibold))
        Text(subtitle(for: arrival))
          .font(.caption2)
          .foregroundStyle(
            accentFill && selected
              ? theme.outgoingTextColor.opacity(Self.selectedSubtitleOpacity)
              : .secondary
          )
      }
      .padding(.horizontal, Self.pillHorizontalPadding)
      .padding(.vertical, Self.pillVerticalPadding)
      .foregroundStyle(accentFill && selected ? theme.outgoingTextColor : .primary)
      .contentShape(.capsule)
    }
    .buttonStyle(.plain)
    .arrivalCapsuleChrome(chrome, selected: selected, id: arrival.id, glassNamespace: glassNamespace)
    .accessibilityAddTraits(selected ? .isSelected : [])

    if let seconds = offsetDuration(fromFirstFor: arrival) {
      button.accessibilityLabel(
        L10n.Chats.Chats.Path.Arrival.offsetAccessibility(title(for: arrival), seconds)
      )
    } else {
      button
    }
  }

  /// Opaque accent chips use Theme.outgoingTextColor. Glass pills keep primary/secondary
  /// because the tint is translucent over the map.
  private var usesAccentFill: Bool {
    switch chrome {
    case .plain:
      true
    case .glass:
      if #available(iOS 26.0, *) { false } else { true }
    }
  }

  private func isSelected(_ arrival: MessagePathArrival) -> Bool {
    arrival.id == MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
  }

  private func isFirst(_ arrival: MessagePathArrival) -> Bool {
    arrival.id == arrivals.first?.id
  }

  private func title(for arrival: MessagePathArrival) -> String {
    let hops = arrival.hopCount == 1
      ? L10n.Chats.Chats.Repeats.Hop.singular
      : L10n.Chats.Chats.Repeats.Hop.plural(arrival.hopCount)
    guard let snr = arrival.snr else { return hops }
    let snrText = snr.formatted(.number.precision(.fractionLength(1)))
      + " "
      + L10n.RemoteNodes.RemoteNodes.Status.snrBadgeUnit
    return "\(hops) · \(snrText)"
  }

  private func subtitle(for arrival: MessagePathArrival) -> String {
    if isFirst(arrival) {
      return L10n.Chats.Chats.Path.Arrival.first
    }
    guard let seconds = offsetDuration(fromFirstFor: arrival) else {
      return L10n.Chats.Chats.Path.Arrival.first
    }
    return L10n.Chats.Chats.Path.Arrival.offset(seconds)
  }

  private func offsetDuration(fromFirstFor arrival: MessagePathArrival) -> String? {
    guard let first = arrivals.first, arrival.id != first.id else { return nil }
    let delta = max(0, arrival.receivedAt.timeIntervalSince(first.receivedAt))
    return Duration.seconds(delta).formatted(.units(allowed: [.seconds], width: .narrow))
  }
}

private extension View {
  @ViewBuilder
  func arrivalCapsuleChrome(
    _ chrome: MessagePathArrivalCapsules.Chrome,
    selected: Bool,
    id: UUID,
    glassNamespace: Namespace.ID
  ) -> some View {
    switch chrome {
    case .plain:
      background(selected ? Color.accentColor : Color.clear, in: .capsule)
    case .glass:
      if #available(iOS 26.0, *) {
        glassEffect(
          selected ? .regular.tint(Color.accentColor).interactive() : .regular.interactive(),
          in: .capsule
        )
        .glassEffectID(id, in: glassNamespace)
      } else {
        background(selected ? Color.accentColor : Color.clear, in: .capsule)
      }
    }
  }
}
