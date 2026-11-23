import SwiftUI

private let minimumHitTarget: CGFloat = 44

/// Live or paused state for the RX Log. This is the only view that reads the
/// pending buffer, so a paused arrival re-evaluates the bar and not the list.
struct RxLogStatusBar: View {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  let viewModel: RxLogViewModel
  /// Rows currently on screen. Passed in so the bar does not recompute the list.
  let shownCount: Int
  let isConnected: Bool
  let onJumpToTop: () -> Void

  var body: some View {
    HStack(spacing: 8) {
      Button(action: handleTap) {
        HStack(spacing: 6) {
          statusIndicator

          Text(statusTitle)
            .font(.subheadline)
            .foregroundStyle(.secondary)

          Text("·")
            .font(.subheadline)
            .foregroundStyle(.tertiary)

          Text(statusDetail)
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .modifier(GlassEffectModifier())
        .frame(minWidth: minimumHitTarget, minHeight: minimumHitTarget)
        .contentShape(.rect)
      }
      .buttonStyle(.plain)
      .accessibilityLabel("\(statusTitle), \(statusDetail)")
      .accessibilityHint(
        viewModel.isPaused
          ? L10n.Tools.Tools.RxLog.resumeHint
          : L10n.Tools.Tools.RxLog.pauseHint
      )

      Spacer(minLength: 8)

      if viewModel.hasActiveFilters {
        Button {
          viewModel.clearFilters()
        } label: {
          Text(L10n.Tools.Tools.RxLog.clearFilters)
            .font(.subheadline)
            .frame(minWidth: minimumHitTarget, minHeight: minimumHitTarget)
            .contentShape(.rect)
        }
      }
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 4)
    .modifier(GlassContainerModifier())
  }

  @ViewBuilder
  private var statusIndicator: some View {
    if viewModel.isPaused {
      Image(systemName: "pause.fill")
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityHidden(true)
    } else {
      Circle()
        .fill(isConnected ? Color.green : Color.gray)
        .frame(width: 8, height: 8)
        .modifier(PulseAnimationModifier(isActive: isConnected && !reduceMotion))
        .accessibilityHidden(true)
    }
  }

  private var statusTitle: String {
    if viewModel.isPaused {
      return L10n.Tools.Tools.RxLog.paused
    }
    return isConnected ? L10n.Tools.Tools.RxLog.live : L10n.Tools.Tools.RxLog.offline
  }

  private var statusDetail: String {
    if viewModel.isPaused {
      return L10n.Tools.Tools.RxLog.newPackets(viewModel.pendingVisibleCount)
    }
    if shownCount == viewModel.entries.count {
      return L10n.Tools.Tools.RxLog.packetsCount(viewModel.entries.count)
    }
    return L10n.Tools.Tools.RxLog.packetsFilteredCount(shownCount, viewModel.entries.count)
  }

  /// Clear a user pause before the scroll-away flag so held arrivals flush
  /// before the jump.
  private func handleTap() {
    guard viewModel.isPaused else {
      viewModel.toggleUserPause()
      return
    }
    if viewModel.isUserPaused {
      viewModel.toggleUserPause()
    }
    if viewModel.isScrolledAway {
      viewModel.setScrolledAway(false)
    }
    onJumpToTop()
  }
}

private struct GlassEffectModifier: ViewModifier {
  func body(content: Content) -> some View {
    if #available(iOS 26.0, *) {
      content.glassEffect()
    } else {
      content.background(.ultraThinMaterial, in: .capsule)
    }
  }
}

private struct GlassContainerModifier: ViewModifier {
  func body(content: Content) -> some View {
    if #available(iOS 26.0, *) {
      GlassEffectContainer { content }
    } else {
      content
    }
  }
}

private struct PulseAnimationModifier: ViewModifier {
  let isActive: Bool

  @State private var isPulsing = false

  func body(content: Content) -> some View {
    content
      .opacity(isActive && isPulsing ? 0.4 : 1.0)
      .animation(
        isActive ? .easeInOut(duration: 1.0).repeatForever(autoreverses: true) : .default,
        value: isPulsing
      )
      .onAppear {
        if isActive {
          isPulsing = true
        }
      }
      .onChange(of: isActive) { _, newValue in
        isPulsing = newValue
      }
  }
}
