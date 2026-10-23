import SwiftUI

struct ContactDetailIdentityPreference: PreferenceKey {
  static var defaultValue: UUID? {
    nil
  }

  static func reduce(value: inout UUID?, nextValue: () -> UUID?) {
    value = nextValue() ?? value
  }
}
