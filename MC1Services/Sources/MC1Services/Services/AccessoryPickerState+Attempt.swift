import Foundation

extension AccessoryPickerState {
  struct Attempt {
    let id: UUID
    let presentedAt: Date
    let filteredDiscovery: Bool
    var hasPresented = false
    var outcome: Outcome
  }
}
