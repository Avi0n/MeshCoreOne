import Foundation

/// Identifiable wrapper so a generated debug-log export file can drive `.sheet(item:)`.
struct ExportedLogFile: Identifiable {
    let id = UUID()
    let url: URL
}
