extension AccessoryPickerState {
  enum Outcome: String {
    case presented
    case selected
    case cancelled
    case cancelledAfterSelection
    case orphanedAfterCancellation
    case pairingFailed
    case pickerRestricted
    case pickerAlreadyActive
    case discoveryTimeout
    case connectionFailed
  }
}
