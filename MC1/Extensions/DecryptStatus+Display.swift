import MC1Services

extension DecryptStatus {
  /// Localized display label for decrypt-status indicators.
  var localizedName: String {
    switch self {
    case .notApplicable: L10n.Tools.Tools.RxLog.DecryptStatus.notApplicable
    case .noMatchingKey: L10n.Tools.Tools.RxLog.DecryptStatus.noKey
    case .hmacFailed: L10n.Tools.Tools.RxLog.DecryptStatus.hmacFailed
    case .decryptFailed: L10n.Tools.Tools.RxLog.DecryptStatus.decryptFailed
    case .success: L10n.Tools.Tools.RxLog.DecryptStatus.decrypted
    case .pending: L10n.Tools.Tools.RxLog.DecryptStatus.hasKey
    case .dmNoMatchingKey: L10n.Tools.Tools.RxLog.DecryptStatus.noDmKey
    }
  }
}
