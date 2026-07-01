import MC1Services
import SwiftUI
import UIKit

extension SNRQuality {
  /// SwiftUI color for signal quality indicators.
  var color: Color {
    switch self {
    case .excellent, .good: .green
    case .fair: .yellow
    case .poor: .red
    case .unknown: .secondary
    }
  }

  /// UIKit color for MapKit renderers.
  var uiColor: UIColor {
    switch self {
    case .excellent, .good: .systemGreen
    case .fair: .systemYellow
    case .poor: .systemRed
    case .unknown: .systemGray
    }
  }

  /// Localized display label for signal quality.
  var localizedLabel: String {
    switch self {
    case .excellent: L10n.Chats.Chats.Signal.excellent
    case .good: L10n.Chats.Chats.Signal.good
    case .fair: L10n.Chats.Chats.Signal.fair
    case .poor: L10n.Chats.Chats.Signal.poor
    case .unknown: L10n.Chats.Chats.Path.Hop.signalUnknown
    }
  }
}
