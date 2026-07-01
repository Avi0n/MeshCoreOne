import SwiftUI

enum CLIOutputType {
  case command // User-entered command (echoed)
  case success // Success/acknowledgment
  case error // Error message
  case response // Node response data

  var color: Color {
    switch self {
    case .command: .secondary
    case .success: .green
    case .error: .orange
    case .response: .primary
    }
  }
}

struct CLIOutputLine: Identifiable {
  let id = UUID()
  let text: String
  let type: CLIOutputType
  let timestamp = Date()
}
