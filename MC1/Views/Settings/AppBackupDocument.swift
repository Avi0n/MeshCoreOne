import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let mc1Backup = UTType(exportedAs: "com.pocketmesh.mc1.backup")
}

struct AppBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.mc1Backup] }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
