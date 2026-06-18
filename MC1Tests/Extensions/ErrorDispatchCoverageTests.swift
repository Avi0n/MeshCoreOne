import Foundation
import Testing

/// Drift test: fails when a new LocalizedError enum is added to MC1Services or MeshCore
/// without adding a dispatch arm to Error+UserFacingMessage.swift.
///
/// Mechanism: at test runtime the simulator shares the host filesystem, so #filePath
/// resolves to the source tree. The test scans Swift sources for LocalizedError enum
/// declarations and extension conformances, then asserts every discovered name is
/// either dispatched in Error+UserFacingMessage.swift or explicitly allowlisted with
/// a reason.
///
/// When this test fails: add a `case let error as <NewType>: error.userFacingMessage`
/// arm to Error+UserFacingMessage.swift and the matching +UserFacingMessage extension
/// file, or add the type to `intentionallyUnmappedTypes` with a one-line justification
/// if it never reaches .errorAlert.
@Suite("ErrorDispatchCoverage")
struct ErrorDispatchCoverageTests {

    // MARK: - Allowlist

    /// Types that implement LocalizedError but are intentionally omitted from the
    /// Error+UserFacingMessage dispatcher because they never reach .errorAlert.
    ///
    /// Each entry includes a one-line justification. The test will fail if an
    /// allowlisted name no longer appears in the source tree (stale allowlist).
    private static let intentionallyUnmappedTypes: [String: String] = [
        "PairingError": "Routed through ConnectionUIState.presentPairingFailure; "
            + "produces bespoke auth-failure vs generic-failure alerts, not a plain message.",
        "DevicePairingError": "Control-flow signal (cancelled / alreadyInProgress); "
            + "caught at every call site before the error reaches .errorAlert.",
    ]

    // MARK: - Test

    @Test("Every LocalizedError enum in MC1Services and MeshCore has a dispatch arm or is allowlisted")
    func everyLocalizedErrorEnumIsDispatched() throws {
        let repoRoot = try resolveRepoRoot()
        let discovered = try discoverLocalizedErrorEnumNames(under: repoRoot)
        let dispatched = try dispatchedTypeNames(in: repoRoot)
        let allowlisted = Set(Self.intentionallyUnmappedTypes.keys)

        let unmapped = discovered.subtracting(dispatched).subtracting(allowlisted)
        let staleAllowList = allowlisted.subtracting(discovered)

        #expect(unmapped.isEmpty, """
        LocalizedError enums without a dispatch arm or allowlist entry: \(unmapped.sorted()).
        Add a `case let error as <Type>: error.userFacingMessage` arm to
        Error+UserFacingMessage.swift and a matching +UserFacingMessage file,
        OR add the type to intentionallyUnmappedTypes with a justification.
        """)

        #expect(staleAllowList.isEmpty, """
        intentionallyUnmappedTypes entries whose type name no longer appears in the source tree: \
        \(staleAllowList.sorted()).
        Remove stale entries from intentionallyUnmappedTypes.
        """)
    }

    // MARK: - Source scanning helpers

    /// Resolves the repo root from this file's compile-time path.
    /// This file lives at MC1Tests/Extensions/ErrorDispatchCoverageTests.swift,
    /// so the root is three parent directories up.
    private func resolveRepoRoot(filePath: StaticString = #filePath) throws -> URL {
        let thisFile = URL(fileURLWithPath: "\(filePath)")
        // MC1Tests/Extensions/ErrorDispatchCoverageTests.swift -> go up 3 levels
        let root = thisFile
            .deletingLastPathComponent() // Extensions/
            .deletingLastPathComponent() // MC1Tests/
            .deletingLastPathComponent() // repo root
        guard FileManager.default.fileExists(atPath: root.path) else {
            throw CocoaError(.fileNoSuchFile,
                             userInfo: [NSFilePathErrorKey: root.path])
        }
        return root
    }

    /// Scans MC1Services/Sources and MeshCore/Sources for Swift files and extracts
    /// every type name that conforms to LocalizedError (directly or via extension).
    ///
    /// Patterns matched:
    ///   - `enum Foo: ..., LocalizedError, ...`
    ///   - `extension Foo: LocalizedError` / `extension Foo: @retroactive LocalizedError`
    private func discoverLocalizedErrorEnumNames(under root: URL) throws -> Set<String> {
        let sourceDirs = [
            root.appendingPathComponent("MC1Services/Sources"),
            root.appendingPathComponent("MeshCore/Sources"),
        ]

        // Regex: matches `enum Name` where the conformance list contains LocalizedError,
        // or `extension Name: ... LocalizedError ...` (with optional @retroactive).
        let enumPattern = try NSRegularExpression(
            pattern: #"(?m)^\s*(?:public\s+)?enum\s+(\w+)\s*:[^{]*\bLocalizedError\b"#
        )
        // Extension conformances, covering multi-protocol lists and @retroactive:
        // `extension Foo: LocalizedError`, `extension Foo: Bar, LocalizedError`
        let multiExtensionPattern = try NSRegularExpression(
            pattern: #"(?m)^\s*extension\s+(\w+)\s*:[^{]*\bLocalizedError\b"#
        )

        var names = Set<String>()

        for dir in sourceDirs {
            guard let enumerator = FileManager.default.enumerator(
                at: dir,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ) else { continue }

            for case let fileURL as URL in enumerator {
                guard fileURL.pathExtension == "swift" else { continue }
                let source = try String(contentsOf: fileURL, encoding: .utf8)
                let range = NSRange(source.startIndex..., in: source)
                for pattern in [enumPattern, multiExtensionPattern] {
                    let matches = pattern.matches(in: source, range: range)
                    for match in matches {
                        if let nameRange = Range(match.range(at: 1), in: source) {
                            names.insert(String(source[nameRange]))
                        }
                    }
                }
            }
        }

        return names
    }

    /// Scans Error+UserFacingMessage.swift for `case let error as <Name>:` arms.
    private func dispatchedTypeNames(in root: URL) throws -> Set<String> {
        let dispatchFile = root.appendingPathComponent(
            "MC1/Extensions/Errors/Error+UserFacingMessage.swift"
        )
        let source = try String(contentsOf: dispatchFile, encoding: .utf8)
        let pattern = try NSRegularExpression(
            pattern: #"case\s+let\s+\w+\s+as\s+(\w+)\s*:"#
        )
        let range = NSRange(source.startIndex..., in: source)
        let matches = pattern.matches(in: source, range: range)
        return Set(matches.compactMap { match -> String? in
            guard let nameRange = Range(match.range(at: 1), in: source) else { return nil }
            return String(source[nameRange])
        })
    }
}
