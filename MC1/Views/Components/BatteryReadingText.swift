import Foundation
import MC1Services

enum BatteryReadingText {
  static func string(millivolts: Int, ocvArray: [Int]) -> String {
    let volts = Double(millivolts) / 1000
    let percent = BatteryInfo(level: millivolts).percentage(using: ocvArray)
    return "\(volts.formatted(.number.precision(.fractionLength(3))))V (\(percent)%)"
  }
}
