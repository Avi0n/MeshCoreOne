import Foundation

/// Forget prompt for ASK accessories that have no `Device` row.
struct SystemPairingSetupPrompt: Identifiable, Equatable {
  let id = UUID()
  let accessories: [SystemPairedAccessory]
}
