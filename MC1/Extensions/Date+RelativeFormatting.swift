import Foundation

extension Date {
  var relativeFormatted: String {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter.localizedString(for: self, relativeTo: Date())
  }
}
