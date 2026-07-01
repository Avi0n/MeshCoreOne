import MeshCore
import SwiftUI

/// Display helpers for battery information.
/// Consolidates LiPo voltage-to-percentage calculation previously duplicated in
/// BLEStatusIndicatorView and DeviceInfoView.
extension BatteryInfo {
  /// Whether this reading represents a real battery.
  /// 0mV indicates no battery hardware (e.g., mains-powered device with no ADC pin).
  var isBatteryPresent: Bool {
    level > 0
  }

  /// Battery voltage in volts (converted from millivolts)
  var voltage: Double {
    Double(level) / 1000.0
  }

  /// Estimated percentage based on LiPo curve (4.2V = 100%, 3.0V = 0%)
  var percentage: Int {
    let percent = ((voltage - 3.0) / 1.2) * 100
    return Int(min(100, max(0, percent)))
  }

  /// Calculate percentage using OCV array lookup with linear interpolation.
  /// The OCV array should have 11 values mapping to 100%, 90%, 80%... 0%.
  func percentage(using ocvArray: [Int]) -> Int {
    guard ocvArray.count == 11 else { return percentage } // Fallback to linear

    let millivolts = level

    // Above max voltage = 100%
    if millivolts >= ocvArray[0] {
      return 100
    }

    // Below min voltage = 0%
    if millivolts <= ocvArray[10] {
      return 0
    }

    // Find segment and interpolate
    for index in 0..<10 {
      let upperV = ocvArray[index]
      let lowerV = ocvArray[index + 1]
      if millivolts >= lowerV {
        let segmentPercent = Double(millivolts - lowerV) / Double(upperV - lowerV)
        let basePercent = (10 - index - 1) * 10 // 90, 80, 70, ...
        return basePercent + Int((segmentPercent * 10).rounded())
      }
    }

    return 0
  }

  /// SF Symbol name for battery level
  var iconName: String {
    switch percentage {
    case 88...100: "battery.100"
    case 63..<88: "battery.75"
    case 38..<63: "battery.50"
    case 13..<38: "battery.25"
    default: "battery.0"
    }
  }

  /// SF Symbol name for battery level using OCV array
  func iconName(using ocvArray: [Int]) -> String {
    switch percentage(using: ocvArray) {
    case 88...100: "battery.100"
    case 63..<88: "battery.75"
    case 38..<63: "battery.50"
    case 13..<38: "battery.25"
    default: "battery.0"
    }
  }

  /// Color for battery display based on level
  var levelColor: Color {
    switch percentage {
    case 20...100: .primary
    case 10..<20: .orange
    default: .red
    }
  }

  /// Color for battery display based on OCV level
  func levelColor(using ocvArray: [Int]) -> Color {
    switch percentage(using: ocvArray) {
    case 20...100: .primary
    case 10..<20: .orange
    default: .red
    }
  }
}
