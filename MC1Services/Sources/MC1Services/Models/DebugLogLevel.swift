import Foundation

public enum DebugLogLevel: Int, Sendable, CaseIterable {
  case debug = 0
  case info = 1
  case notice = 2
  case warning = 3
  case error = 4
  case fault = 5

  public var label: String {
    switch self {
    case .debug: "DEBUG"
    case .info: "INFO"
    case .notice: "NOTICE"
    case .warning: "WARNING"
    case .error: "ERROR"
    case .fault: "FAULT"
    }
  }
}
