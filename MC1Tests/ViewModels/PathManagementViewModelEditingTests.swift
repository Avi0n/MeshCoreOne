import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("PathManagementViewModel Editing")
struct PathManagementViewModelEditingTests {
  // MARK: - PickerNode.isFavorite

  @Test
  func `Contact with isFavorite=true reports favorite`() {
    let contact = ContactDTO.fixture(isFavorite: true)
    let node = PickerNode.contact(contact)
    #expect(node.isFavorite == true)
  }

  @Test
  func `Discovered node is never favorite`() {
    let discovered = DiscoveredNodeDTO.fixture()
    let node = PickerNode.discovered(discovered)
    #expect(node.isFavorite == false)
  }

  // MARK: - insert(_:at:)

  @Test
  @MainActor
  func `insert with .append adds at end of editablePath`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    let node = ContactDTO.fixture(name: "A", publicKey: Data(repeating: 0x01, count: 32))
    vm.insert(node, at: .append)
    #expect(vm.editablePath.count == 1)
    #expect(vm.editablePath[0].publicKey == node.publicKey)
  }

  @Test
  @MainActor
  func `insert preserves insertionIntent so picker stays for multi-add`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    vm.insertionIntent = .append
    vm.insert(ContactDTO.fixture(), at: .append)
    #expect(vm.insertionIntent == .append)
  }

  // MARK: - initializeEditablePath

  @Test
  @MainActor
  func `initializeEditablePath clears a stale insertionIntent`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    vm.insertionIntent = .append
    let contact = ContactDTO.fixture() // default outPathLength = 0xFF (flood, no path)
    vm.initializeEditablePath(from: contact)
    #expect(vm.insertionIntent == nil)
  }

  @Test
  @MainActor
  func `initializeEditablePath resolves publicKey for uniquely-matching contact`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    let radioID = UUID()
    let fullKey = Data([0xA1, 0xB2, 0xC3] + Array(repeating: 0x00, count: 29))
    let repeater = ContactDTO.fixture(
      name: "Hop1",
      publicKey: fullKey,
      type: .repeater,
      radioID: radioID
    )
    vm.allContacts = [repeater]

    // Stored path: one hop, hashSize=1 → 1 byte (0xA1). pathLength = (0<<6)|1 = 0x01.
    let contact = ContactDTO.fixture(
      name: "Target",
      publicKey: Data(repeating: 0xFE, count: 32),
      radioID: radioID,
      outPathLength: 0x01,
      outPath: Data([0xA1])
    )
    vm.initializeEditablePath(from: contact)
    #expect(vm.editablePath.count == 1)
    #expect(vm.editablePath[0].publicKey == fullKey)
    #expect(vm.editablePath[0].resolvedName == "Hop1")
  }

  // MARK: - resolveHashToPublicKey

  @Test
  @MainActor
  func `resolveHashToPublicKey returns nil when multiple contacts share a prefix`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    let keyA = Data([0xA1, 0x00] + Array(repeating: 0x00, count: 30))
    let keyB = Data([0xA1, 0x01] + Array(repeating: 0x00, count: 30))
    vm.allContacts = [
      ContactDTO.fixture(name: "A", publicKey: keyA),
      ContactDTO.fixture(name: "B", publicKey: keyB)
    ]
    #expect(vm.resolveHashToPublicKey(Data([0xA1])) == nil)
  }

  @Test
  @MainActor
  func `resolveHashToPublicKey falls through to discovered when contacts have no match`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    let fullKey = Data([0xDD, 0xEE] + Array(repeating: 0x00, count: 30))
    vm.allContacts = []
    vm.discoveredNodes = [DiscoveredNodeDTO.fixture(publicKey: fullKey)]
    #expect(vm.resolveHashToPublicKey(Data([0xDD])) == fullKey)
  }

  @Test
  @MainActor
  func `resolveHashToPublicKey ignores discovered when contacts are ambiguous`() {
    // Deliberate rule shared with `resolveHashToName`: ambiguity in contacts
    // never falls through to discovered, since an ambiguous contact match
    // could itself be the correct target.
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    let keyA = Data([0xA1, 0x00] + Array(repeating: 0x00, count: 30))
    let keyB = Data([0xA1, 0x01] + Array(repeating: 0x00, count: 30))
    let discoveredKey = Data([0xA1, 0x02] + Array(repeating: 0x00, count: 30))
    vm.allContacts = [
      ContactDTO.fixture(name: "A", publicKey: keyA),
      ContactDTO.fixture(name: "B", publicKey: keyB)
    ]
    vm.discoveredNodes = [DiscoveredNodeDTO.fixture(publicKey: discoveredKey)]
    #expect(vm.resolveHashToPublicKey(Data([0xA1])) == nil)
  }

  // MARK: - saveRejection (B-1 guard)

  @Test
  func `saveRejection flags hop missing publicKey when target hashSize grew`() {
    // Stored with hashSize=1; device now reports hashSize=2.
    let hops = [PathHop(hashBytes: Data([0xA1]), publicKey: nil, resolvedName: nil)]
    let rejection = PathManagementViewModel.saveRejection(
      for: hops,
      targetHashSize: 2,
      maxHopCount: 32
    )
    #expect(rejection != nil)
  }

  @Test
  func `saveRejection allows widening when publicKey is available`() {
    // Hop carries full pubkey → branch (a) can re-encode at the wider size.
    let pk = Data([0xA1, 0xB2] + Array(repeating: 0x00, count: 30))
    let hops = [PathHop(hashBytes: Data([0xA1]), publicKey: pk, resolvedName: "H1")]
    let rejection = PathManagementViewModel.saveRejection(
      for: hops,
      targetHashSize: 2,
      maxHopCount: 32
    )
    #expect(rejection == nil)
  }

  @Test
  func `saveRejection allows same-size encode even without publicKey`() {
    // Stored with hashSize=1, device still hashSize=1 → fallback prefix(1) of 1 byte is 1 byte.
    let hops = [PathHop(hashBytes: Data([0xA1]), publicKey: nil, resolvedName: nil)]
    let rejection = PathManagementViewModel.saveRejection(
      for: hops,
      targetHashSize: 1,
      maxHopCount: 63
    )
    #expect(rejection == nil)
  }

  @Test
  func `saveRejection flags hop-count over budget`() {
    // 22 hops × 3 bytes = 66 > 64-byte budget → maxHopCount for hashSize=3 is 21.
    let hops = (0..<22).map { i -> PathHop in
      let pk = Data([UInt8(i), 0x00, 0x00] + Array(repeating: 0x00, count: 29))
      return PathHop(hashBytes: Data(pk.prefix(3)), publicKey: pk, resolvedName: "H\(i)")
    }
    let rejection = PathManagementViewModel.saveRejection(
      for: hops,
      targetHashSize: 3,
      maxHopCount: 21
    )
    #expect(rejection != nil)
  }

  // MARK: - encodeEditablePath

  @Test
  func `encodeEditablePath uses full publicKey when available`() {
    let pk1 = Data([0xA1, 0xB2] + Array(repeating: 0x00, count: 30))
    let pk2 = Data([0xC3, 0xD4] + Array(repeating: 0x00, count: 30))
    let hops = [
      PathHop(hashBytes: Data([0xA1]), publicKey: pk1, resolvedName: "H1"),
      PathHop(hashBytes: Data([0xC3]), publicKey: pk2, resolvedName: "H2")
    ]
    let encoded = PathManagementViewModel.encodeEditablePath(hops, targetHashSize: 2)
    #expect(encoded.path == Data([0xA1, 0xB2, 0xC3, 0xD4]))
    // pathLength = (hashSize - 1) << 6 | hopCount = (2-1)<<6 | 2 = 0x42
    #expect(encoded.length == 0x42)
  }

  @Test
  func `encodeEditablePath truncates publicKey to targetHashSize`() {
    // Stored hashSize=2, device narrowed to 1 → first byte only.
    let pk = Data([0xA1, 0xB2] + Array(repeating: 0x00, count: 30))
    let hops = [PathHop(hashBytes: Data([0xA1, 0xB2]), publicKey: pk, resolvedName: "H1")]
    let encoded = PathManagementViewModel.encodeEditablePath(hops, targetHashSize: 1)
    #expect(encoded.path == Data([0xA1]))
    #expect(encoded.length == 0x01)
  }

  @Test
  func `encodeEditablePath falls back to hashBytes when publicKey is nil`() {
    // Same-size fallback: stored 2-byte hops, device hashSize=2.
    let hops = [
      PathHop(hashBytes: Data([0xA1, 0xB2]), publicKey: nil, resolvedName: nil),
      PathHop(hashBytes: Data([0xC3, 0xD4]), publicKey: nil, resolvedName: nil)
    ]
    let encoded = PathManagementViewModel.encodeEditablePath(hops, targetHashSize: 2)
    #expect(encoded.path == Data([0xA1, 0xB2, 0xC3, 0xD4]))
    #expect(encoded.length == 0x42)
  }

  // MARK: - normalizeHop (Bug B: mixed-width editablePath after pathHashMode change)

  @Test
  func `normalizeHop widens via publicKey when target grew`() {
    // Stored at hashSize=1 (1 byte), device now reports hashSize=3 (3 bytes).
    // A resolved hop can widen by slicing the full 32-byte key.
    let pk = Data([0xA1, 0xB2, 0xC3] + Array(repeating: 0x00, count: 29))
    let hop = PathHop(hashBytes: Data([0xA1]), publicKey: pk, resolvedName: "H1")
    let normalized = PathManagementViewModel.normalizeHop(hop, targetHashSize: 3)
    #expect(normalized.hashBytes == Data([0xA1, 0xB2, 0xC3]))
    #expect(normalized.publicKey == pk)
    #expect(normalized.resolvedName == "H1")
  }

  @Test
  func `normalizeHop narrows via publicKey when target shrank`() {
    let pk = Data([0xA1, 0xB2, 0xC3] + Array(repeating: 0x00, count: 29))
    let hop = PathHop(hashBytes: Data([0xA1, 0xB2, 0xC3]), publicKey: pk, resolvedName: "H1")
    let normalized = PathManagementViewModel.normalizeHop(hop, targetHashSize: 1)
    #expect(normalized.hashBytes == Data([0xA1]))
    #expect(normalized.publicKey == pk)
  }

  @Test
  func `normalizeHop narrows via hashBytes when publicKey unresolved`() {
    // Unresolved hop wider than the target — truncate from the stored bytes.
    let hop = PathHop(
      hashBytes: Data([0xA1, 0xB2, 0xC3]),
      publicKey: nil,
      resolvedName: nil
    )
    let normalized = PathManagementViewModel.normalizeHop(hop, targetHashSize: 1)
    #expect(normalized.hashBytes == Data([0xA1]))
    #expect(normalized.publicKey == nil)
  }

  @Test
  func `normalizeHop leaves narrower unresolved hop unchanged`() {
    // Unresolved, narrower than target — can't widen, saveRejection will block.
    let hop = PathHop(hashBytes: Data([0xA1]), publicKey: nil, resolvedName: nil)
    let normalized = PathManagementViewModel.normalizeHop(hop, targetHashSize: 3)
    #expect(normalized.hashBytes == Data([0xA1]),
            "Narrower unresolved hops remain short so saveRejection can catch them")
    #expect(normalized.publicKey == nil)
  }

  @Test
  func `normalizeHop widens via publicKey even when stored is already at target width`() {
    // Defensive: resolved hop at the correct width — normalize is idempotent.
    let pk = Data([0xA1, 0xB2] + Array(repeating: 0x00, count: 30))
    let hop = PathHop(hashBytes: Data([0xA1, 0xB2]), publicKey: pk, resolvedName: "H1")
    let normalized = PathManagementViewModel.normalizeHop(hop, targetHashSize: 2)
    #expect(normalized.hashBytes == Data([0xA1, 0xB2]))
  }

  // MARK: - initializeEditablePath normalization (integration)

  @Test
  @MainActor
  func `initializeEditablePath narrows wider stored hops when resolution fails`() {
    // Default device hashSize=1 (no AppState). Stored mode=1 (2B/hop), unresolvable hop.
    // After load, hashBytes should be truncated to 1 byte so encodeEditablePath
    // emits a width-consistent buffer.
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    vm.allContacts = [] // nothing resolves

    // pathLength = (1 << 6) | 1 = 0x41 (mode 1, 1 hop) → 2 bytes on wire.
    let contact = ContactDTO.fixture(
      name: "Target",
      outPathLength: 0x41,
      outPath: Data([0xA1, 0xB2])
    )
    vm.initializeEditablePath(from: contact)
    #expect(vm.editablePath.count == 1)
    #expect(vm.editablePath[0].hashBytes == Data([0xA1]),
            "Wider stored hops should narrow to the device's hashSize on load")
    #expect(vm.editablePath[0].publicKey == nil)
  }

  @Test
  @MainActor
  func `initializeEditablePath resliced resolved hop to device hashSize`() {
    // Default device hashSize=1. Stored mode=1 (2B/hop), resolvable hop.
    // hashBytes should collapse to publicKey.prefix(1) — width matches the device.
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    let radioID = UUID()
    let fullKey = Data([0xA1, 0xB2, 0xC3] + Array(repeating: 0x00, count: 29))
    vm.allContacts = [
      ContactDTO.fixture(
        name: "Hop1",
        publicKey: fullKey,
        type: .repeater,
        radioID: radioID
      )
    ]
    let contact = ContactDTO.fixture(
      name: "Target",
      publicKey: Data(repeating: 0xFE, count: 32),
      radioID: radioID,
      outPathLength: 0x41, // mode 1, 1 hop
      outPath: Data([0xA1, 0xB2])
    )
    vm.initializeEditablePath(from: contact)
    #expect(vm.editablePath.count == 1)
    #expect(vm.editablePath[0].publicKey == fullKey)
    #expect(vm.editablePath[0].hashBytes == Data([0xA1]),
            "Resolved hop should be re-sliced to the device's hashSize")
  }

  @Test
  func `initializeEditablePath leaves narrower unresolved hop unchanged so saveRejection blocks save`() {
    // Device's connectedDevice isn't reachable from tests, so this test
    // walks the same normalize → saveRejection pipeline that
    // `initializeEditablePath` runs under a wider `hashSize`. Stored path:
    // mode 0 (1B/hop), 1 hop, unresolvable. Target device hashSize=3 (mode 2).
    let storedHashSize = 1
    let storedPath = Data([0xA1])
    let targetHashSize = 3
    let targetMaxHopCount = 21 // 64-byte payload budget / 3 bytes per hop

    let hops = stride(from: 0, to: storedPath.count, by: storedHashSize).map { start in
      let end = min(start + storedHashSize, storedPath.count)
      let bytes = Data(storedPath[start..<end])
      let hop = PathHop(hashBytes: bytes, publicKey: nil, resolvedName: nil)
      return PathManagementViewModel.normalizeHop(hop, targetHashSize: targetHashSize)
    }

    #expect(hops.count == 1)
    #expect(hops[0].hashBytes == Data([0xA1]),
            "Narrower unresolved hop must stay short so saveRejection can flag it.")
    #expect(hops[0].publicKey == nil)

    let rejection = PathManagementViewModel.saveRejection(
      for: hops,
      targetHashSize: targetHashSize,
      maxHopCount: targetMaxHopCount
    )
    #expect(rejection != nil,
            "saveRejection must refuse to save a path whose unresolved hops can't widen to the device's hash size.")
  }

  // MARK: - Max-hop guard

  @Test
  @MainActor
  func `isPathFull is false with no hops at hashSize=1`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    #expect(vm.isPathFull == false)
    #expect(vm.maxHopCount == 63) // default hashSize=1 → hop-count field is the cap
  }

  @Test
  @MainActor
  func `insert is a no-op once isPathFull`() {
    let vm = PathManagementViewModel(defaults: makeSuiteDefaults())
    // Seed editablePath to the cap directly to avoid 63 sequential insert calls.
    vm.editablePath = (0..<vm.maxHopCount).map { i in
      PathHop(hashBytes: Data([UInt8(i % 256)]), publicKey: nil, resolvedName: "Hop\(i)")
    }
    #expect(vm.isPathFull == true)
    let blocked = ContactDTO.fixture(name: "Overflow", publicKey: Data(repeating: 0xFF, count: 32))
    vm.insert(blocked, at: .append)
    #expect(vm.editablePath.count == vm.maxHopCount)
    #expect(vm.editablePath.last?.resolvedName != "Overflow")
  }

  // MARK: - matches(_:query:) / isHexQuery(_:)

  @Test
  func `Empty query matches everything`() {
    let node = PickerNode.contact(.fixture(name: "Basecamp"))
    #expect(HopNodeMatching.matches(node, query: "") == true)
  }

  @Test
  func `Name substring is case-insensitive`() {
    let node = PickerNode.contact(.fixture(name: "Basecamp North"))
    #expect(HopNodeMatching.matches(node, query: "bas") == true)
    #expect(HopNodeMatching.matches(node, query: "BASE") == true)
    #expect(HopNodeMatching.matches(node, query: "north") == true)
    #expect(HopNodeMatching.matches(node, query: "zzz") == false)
  }

  @Test
  func `Turkish dotless-I folds correctly`() {
    // localizedCaseInsensitiveContains folds İ↔i per the user's locale.
    // The query "istanbul" should match a name with Turkish İ.
    let node = PickerNode.contact(.fixture(name: "İstanbul Relay"))
    #expect(HopNodeMatching.matches(node, query: "istanbul") == true)
  }

  @Test
  func `Hex prefix matches full pubkey`() {
    // Pubkey a3 f2 00 00 … — query "a3f2" should match via hex branch
    let pk = Data([0xA3, 0xF2] + Array(repeating: 0x00, count: 30))
    let node = PickerNode.contact(.fixture(name: "Unrelated", publicKey: pk))
    #expect(HopNodeMatching.matches(node, query: "a3f2") == true)
    #expect(HopNodeMatching.matches(node, query: "A3F2") == true)
    #expect(HopNodeMatching.matches(node, query: "b7") == false)
  }

  @Test
  func `All-hex query matches both name and pubkey hex`() {
    // Name "A3 Basecamp" and pubkey starting a3… — "a3" matches both branches.
    let pk = Data([0xA3] + Array(repeating: 0x00, count: 31))
    let node = PickerNode.contact(.fixture(name: "A3 Basecamp", publicKey: pk))
    #expect(HopNodeMatching.matches(node, query: "a3") == true)
  }

  @Test
  func `Non-hex query ignores pubkey`() {
    let node = PickerNode.contact(.fixture(name: "Summit"))
    #expect(HopNodeMatching.matches(node, query: "foo") == false)
  }

  @Test
  func `isHexQuery detects valid hex digits`() {
    #expect(HopNodeMatching.isHexQuery("a3f2") == true)
    #expect(HopNodeMatching.isHexQuery("A3F2") == true)
    #expect(HopNodeMatching.isHexQuery("0123456789abcdefABCDEF") == true)
    #expect(HopNodeMatching.isHexQuery("a3z") == false)
    #expect(HopNodeMatching.isHexQuery("") == false)
  }

  // MARK: - filtered(_:by:)

  @Test
  func `filtered returns all nodes for an empty query`() {
    let nodes = [
      PickerNode.contact(.fixture(name: "A")),
      PickerNode.contact(.fixture(name: "B"))
    ]
    let result = HopNodeMatching.filtered(nodes, by: "")
    #expect(result.count == 2)
  }

  @Test
  func `filtered keeps only name-substring matches for a non-hex query`() {
    let nodes = [
      PickerNode.contact(.fixture(name: "Basecamp")),
      PickerNode.contact(.fixture(name: "Summit"))
    ]
    let result = HopNodeMatching.filtered(nodes, by: "base")
    #expect(result.count == 1)
    #expect(result[0].displayName == "Basecamp")
  }

  @Test
  func `filtered hex query matches both name and pubkey-hex prefix branches`() {
    // Node 1: pubkey starts 0xa3; Node 2: name starts "A3"; Node 3 matches neither.
    let pk1 = Data([0xA3] + Array(repeating: 0x00, count: 31))
    let pk2 = Data([0x89] + Array(repeating: 0x00, count: 31))
    let pk3 = Data([0xF0] + Array(repeating: 0x00, count: 31))
    let nodes = [
      PickerNode.contact(.fixture(name: "Foo", publicKey: pk1)),
      PickerNode.contact(.fixture(name: "A3 Basecamp", publicKey: pk2)),
      PickerNode.contact(.fixture(name: "Zeta", publicKey: pk3))
    ]
    let result = HopNodeMatching.filtered(nodes, by: "a3")
    #expect(result.count == 2)
  }

  // MARK: - Recents persistence

  @Test @MainActor
  func `record recent moves to front`() {
    let defaults = makeSuiteDefaults()
    let vm = PathManagementViewModel(defaults: defaults)
    let radioID = UUID()
    vm.loadRecentKeys(for: radioID)
    let pk1 = Data(repeating: 0x01, count: 32)
    let pk2 = Data(repeating: 0x02, count: 32)
    vm.insert(ContactDTO.fixture(publicKey: pk1), at: .append)
    vm.insert(ContactDTO.fixture(publicKey: pk2), at: .append)
    vm.insert(ContactDTO.fixture(publicKey: pk1), at: .append)
    #expect(vm.recentPublicKeys == [pk1, pk2])
  }

  @Test @MainActor
  func `record recent trims to limit`() {
    let defaults = makeSuiteDefaults()
    let vm = PathManagementViewModel(defaults: defaults)
    vm.loadRecentKeys(for: UUID())
    for i: UInt8 in 0..<10 {
      vm.insert(ContactDTO.fixture(publicKey: Data(repeating: i, count: 32)), at: .append)
    }
    #expect(vm.recentPublicKeys.count == 8)
    #expect(vm.recentPublicKeys.first == Data(repeating: 9, count: 32))
    #expect(vm.recentPublicKeys.last == Data(repeating: 2, count: 32))
  }

  @Test @MainActor
  func `recent keys persist across instances`() throws {
    let suite = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    let radioID = UUID()
    let pk = Data(repeating: 0x0A, count: 32)

    let vmA = PathManagementViewModel(defaults: defaults)
    vmA.loadRecentKeys(for: radioID)
    vmA.insert(ContactDTO.fixture(publicKey: pk), at: .append)

    let vmB = PathManagementViewModel(defaults: defaults)
    vmB.loadRecentKeys(for: radioID)
    #expect(vmB.recentPublicKeys == [pk])
  }

  @Test @MainActor
  func `recent keys scoped per radio`() {
    let defaults = makeSuiteDefaults()
    let radioX = UUID()
    let radioY = UUID()
    let pk = Data(repeating: 0x0A, count: 32)

    let vmA = PathManagementViewModel(defaults: defaults)
    vmA.loadRecentKeys(for: radioX)
    vmA.insert(ContactDTO.fixture(publicKey: pk), at: .append)

    let vmB = PathManagementViewModel(defaults: defaults)
    vmB.loadRecentKeys(for: radioY)
    #expect(vmB.recentPublicKeys.isEmpty)
  }

  @Test @MainActor
  func `recent keys stored lowercase`() {
    let defaults = makeSuiteDefaults()
    let vm = PathManagementViewModel(defaults: defaults)
    let radioID = UUID()
    vm.loadRecentKeys(for: radioID)
    let pk = Data([0xAB, 0xCD] + Array(repeating: 0x00, count: 30))
    vm.insert(ContactDTO.fixture(publicKey: pk), at: .append)

    let key = RecentHopsStore.defaultsKey(for: radioID)
    let stored = defaults.stringArray(forKey: key) ?? []
    #expect(stored.count == 1)
    #expect(stored[0] == stored[0].lowercased())
    #expect(stored[0].hasPrefix("abcd"))
  }

  // MARK: - Test helpers

  private func makeSuiteDefaults() -> UserDefaults {
    UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }
}

// MARK: - Test fixtures

extension ContactDTO {
  /// Deterministic ContactDTO for tests. Defaults produce a valid repeater
  /// named "Test Repeater" with a 32-byte pubkey filled with 0xAA and a
  /// flood-routed outbound path.
  static func fixture(
    name: String = "Test Repeater",
    publicKey: Data = Data(repeating: 0xAA, count: 32),
    type: ContactType = .repeater,
    isFavorite: Bool = false,
    radioID: UUID = UUID(),
    outPathLength: UInt8 = 0xFF,
    outPath: Data = Data()
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: publicKey,
      name: name,
      typeRawValue: type.rawValue,
      flags: 0,
      outPathLength: outPathLength,
      outPath: outPath,
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: isFavorite,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }
}

extension DiscoveredNodeDTO {
  /// Deterministic DiscoveredNodeDTO for tests. Defaults produce a repeater
  /// named "Test Discovered" with a 32-byte pubkey filled with 0xBB.
  static func fixture(
    name: String = "Test Discovered",
    publicKey: Data = Data(repeating: 0xBB, count: 32),
    nodeType: ContactType = .repeater,
    radioID: UUID = UUID()
  ) -> DiscoveredNodeDTO {
    DiscoveredNodeDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: publicKey,
      name: name,
      typeRawValue: nodeType.rawValue,
      lastHeard: Date(timeIntervalSince1970: 0),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      outPathLength: 0xFF,
      outPath: Data(),
      inboundHopCount: nil,
      inboundHopAdvertTimestamp: nil
    )
  }
}
