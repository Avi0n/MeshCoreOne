/// Signal quality classification based on LoRa SNR (Signal-to-Noise Ratio) in dB.
///
/// Standard 4-tier scale for signal quality indicators across the app.
public enum SNRQuality: Sendable, Equatable {
  case excellent // SNR > +6 dB
  case good // SNR > +0 dB
  case fair // SNR > -6 dB
  case poor // SNR <= -6 dB
  case unknown // nil SNR

  public init(snr: Double?) {
    guard let snr else {
      self = .unknown
      return
    }
    if snr > 6 { self = .excellent } else if snr > 0 { self = .good } else if snr > -6 { self = .fair } else { self = .poor }
  }

  /// Bar level for SF Symbol `cellularbars` variableValue (0–1).
  public var barLevel: Double {
    switch self {
    case .excellent: 1.0
    case .good: 0.75
    case .fair: 0.5
    case .poor: 0.25
    case .unknown: 0
    }
  }

  /// Developer-facing English label for logs; UI uses the app target's localized `SNRQuality.localizedLabel`.
  public var qualityLabel: String {
    switch self {
    case .excellent: "Excellent"
    case .good: "Good"
    case .fair: "Fair"
    case .poor: "Weak"
    case .unknown: "Unknown"
    }
  }
}
