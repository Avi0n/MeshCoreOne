import Foundation
import Testing
@testable import MC1Services

/// Coverage tests for `BackupUserDefaults`. Asserts every `Bool?`/`String?` property
/// has a matching mapping row, and every `Optional<*>` property is covered by a
/// mapping or the special-cased allow-list.
///
/// Limitation: this test compares SETS of property names and key strings. It cannot
/// detect a swapped `(keyPath, key)` pair where two rows transpose mappings — the
/// stronger pair-wise invariant is recorded in the spec but not asserted here.
///
/// Mirror invariants this test relies on:
/// - `type(of: child.value)` returns the dynamic type even through `Any` boxing.
///   For a default-init struct, all `Optional<T>` properties are `nil`, but
///   `type(of: nil-as-Bool?)` is still `Optional<Bool>.self`. Do not "fix" this
///   test by initializing properties to non-nil values.
/// - Mirror enumerates only stored properties. Computed/lazy/extension properties
///   are not seen. `BackupUserDefaults` has none today.
/// - Mirror reports `@propertyWrapper` storage by its backing label (`_foo`),
///   not the wrapped type. `BackupUserDefaults` uses no property wrappers; if
///   that changes, the type filter silently drops the wrapped property and this
///   test must be revisited.
@Suite("BackupUserDefaultsCoverage")
struct BackupUserDefaultsCoverageTests {

    @Test("Every Bool? property has a boolMappings row")
    func everyBoolPropertyHasMapping() {
        let propertyNames = optionalPropertyNames(matching: Optional<Bool>.self)
        let mappingKeys = BackupUserDefaults.boolMappingKeys

        let missing = propertyNames.subtracting(mappingKeys)
        let extra = mappingKeys.subtracting(propertyNames)

        #expect(missing.isEmpty, """
        Bool? properties without boolMappings rows: \(missing.sorted()).
        Add (\\.<property>, "<property>") to BackupUserDefaults.boolMappings,
        or hand-roll a special-cased branch in snapshot/restore (see regionSelection).
        See BackupUserDefaults.boolMappings.
        """)

        #expect(extra.isEmpty, """
        boolMappings rows without matching Bool? properties: \(extra.sorted()).
        Likely a stale entry after a property rename or removal.
        See BackupUserDefaults.boolMappings.
        """)
    }

    @Test("Every String? property has a stringMappings row")
    func everyStringPropertyHasMapping() {
        let propertyNames = optionalPropertyNames(matching: Optional<String>.self)
        let mappingKeys = BackupUserDefaults.stringMappingKeys

        let missing = propertyNames.subtracting(mappingKeys)
        let extra = mappingKeys.subtracting(propertyNames)

        #expect(missing.isEmpty, """
        String? properties without stringMappings rows: \(missing.sorted()).
        Add (\\.<property>, "<property>") to BackupUserDefaults.stringMappings,
        or hand-roll a special-cased branch in snapshot/restore.
        See BackupUserDefaults.stringMappings.
        """)

        #expect(extra.isEmpty, """
        stringMappings rows without matching String? properties: \(extra.sorted()).
        Likely a stale entry after a property rename or removal.
        See BackupUserDefaults.stringMappings.
        """)
    }

    @Test("Every Optional property is covered by a mapping or special-cased")
    func everyOptionalPropertyIsCovered() {
        let allOptional = allOptionalPropertyNames()
        let covered = BackupUserDefaults.boolMappingKeys
            .union(BackupUserDefaults.stringMappingKeys)
            .union(BackupUserDefaults.specialCasedPropertyNames)

        let uncovered = allOptional.subtracting(covered)
        let staleAllowList = BackupUserDefaults.specialCasedPropertyNames.subtracting(allOptional)

        #expect(uncovered.isEmpty, """
        Optional properties not covered by any mapping or allow-list: \(uncovered.sorted()).
        Either:
          (a) add to boolMappings/stringMappings if Bool?/String?, or
          (b) hand-roll snapshot/restore branches and add to specialCasedPropertyNames.
        See BackupUserDefaults.boolMappings / stringMappings / specialCasedPropertyNames.
        """)

        #expect(staleAllowList.isEmpty, """
        specialCasedPropertyNames entries without matching Optional properties: \(staleAllowList.sorted()).
        Likely a stale allow-list entry after a property rename or removal.
        See BackupUserDefaults.specialCasedPropertyNames.
        """)
    }

    // MARK: - Mirror helpers

    private func optionalPropertyNames<T>(matching: T.Type) -> Set<String> {
        let mirror = Mirror(reflecting: BackupUserDefaults())
        return Set(mirror.children.compactMap { child -> String? in
            guard let label = child.label,
                  type(of: child.value) == T.self else { return nil }
            return label
        })
    }

    private func allOptionalPropertyNames() -> Set<String> {
        let mirror = Mirror(reflecting: BackupUserDefaults())
        return Set(mirror.children.compactMap { child -> String? in
            guard let label = child.label,
                  Mirror(reflecting: child.value).displayStyle == .optional else { return nil }
            return label
        })
    }
}
