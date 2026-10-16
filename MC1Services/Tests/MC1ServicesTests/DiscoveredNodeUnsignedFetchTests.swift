import Foundation
@testable import MC1Services
import MeshCore
import SQLite3
import SwiftData
import Testing

private let discoveredNodeTable = "ZDISCOVEREDNODE"
private let contactTable = "ZCONTACT"
private let outPathLengthColumn = "ZOUTPATHLENGTH"
private let sqliteBusyTimeoutMilliseconds: Int32 = 5000

@Suite("Unsigned path length fetch")
struct DiscoveredNodeUnsignedFetchTests {
  @Test
  func `Backup export of a leftover Int8 flood sentinel (-1)`() async throws {
    let storeURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("discovered-int8-\(UUID().uuidString).store")
    defer {
      let fm = FileManager.default
      try? fm.removeItem(at: storeURL)
      try? fm.removeItem(at: storeURL.appendingPathExtension("shm"))
      try? fm.removeItem(at: storeURL.appendingPathExtension("wal"))
    }

    let radioID = UUID()
    do {
      let cfg = ModelConfiguration(schema: PersistenceStore.schema, url: storeURL)
      let container = try ModelContainer(for: PersistenceStore.schema, configurations: [cfg])
      let store = PersistenceStore(modelContainer: container)
      let frame = ContactFrame(
        publicKey: Data(repeating: 0x44, count: 32),
        type: .repeater,
        flags: 0,
        outPathLength: PacketBuilder.floodPathSentinel,
        outPath: Data(),
        name: "LegacyFlood",
        lastAdvertTimestamp: 1,
        latitude: 0,
        longitude: 0,
        lastModified: 0
      )
      _ = try await store.upsertDiscoveredNode(radioID: radioID, from: frame)
    }

    _ = try sqlite(storeURL, "UPDATE \(discoveredNodeTable) SET \(outPathLengthColumn) = -1;")
    #expect(try sqlite(storeURL, "SELECT \(outPathLengthColumn) FROM \(discoveredNodeTable);") == "-1")

    let cfg = ModelConfiguration(schema: PersistenceStore.schema, url: storeURL)
    let reopened = try ModelContainer(for: PersistenceStore.schema, configurations: [cfg])
    let snapshot = try await PersistenceStore(modelContainer: reopened).fetchBackupExportSnapshot()
    let node = try #require(snapshot.discoveredNodes.first)
    #expect(snapshot.discoveredNodes.count == 1)
    #expect(node.outPathLength == PacketBuilder.floodPathSentinel)
  }

  @Test
  func `Backup export of a leftover Contact Int8 flood sentinel (-1)`() async throws {
    let storeURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("contact-int8-\(UUID().uuidString).store")
    defer {
      let fm = FileManager.default
      try? fm.removeItem(at: storeURL)
      try? fm.removeItem(at: storeURL.appendingPathExtension("shm"))
      try? fm.removeItem(at: storeURL.appendingPathExtension("wal"))
    }

    let radioID = UUID()
    do {
      let cfg = ModelConfiguration(schema: PersistenceStore.schema, url: storeURL)
      let container = try ModelContainer(for: PersistenceStore.schema, configurations: [cfg])
      let store = PersistenceStore(modelContainer: container)
      let frame = ContactFrame(
        publicKey: Data(repeating: 0x55, count: 32),
        type: .chat,
        flags: 0,
        outPathLength: PacketBuilder.floodPathSentinel,
        outPath: Data(),
        name: "LegacyContact",
        lastAdvertTimestamp: 1,
        latitude: 0,
        longitude: 0,
        lastModified: 0
      )
      _ = try await store.saveContact(radioID: radioID, from: frame)
    }

    _ = try sqlite(storeURL, "UPDATE \(contactTable) SET \(outPathLengthColumn) = -1;")
    #expect(try sqlite(storeURL, "SELECT \(outPathLengthColumn) FROM \(contactTable);") == "-1")

    let cfg = ModelConfiguration(schema: PersistenceStore.schema, url: storeURL)
    let reopened = try ModelContainer(for: PersistenceStore.schema, configurations: [cfg])
    let snapshot = try await PersistenceStore(modelContainer: reopened).fetchBackupExportSnapshot()
    let contact = try #require(snapshot.contacts.first)
    #expect(snapshot.contacts.count == 1)
    #expect(contact.outPathLength == PacketBuilder.floodPathSentinel)
  }
}

private func sqlite(_ storeURL: URL, _ sql: String) throws -> String {
  var db: OpaquePointer?
  let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
  guard sqlite3_open_v2(storeURL.path, &db, flags, nil) == SQLITE_OK, let db else {
    throw SQLiteProbeError(message: "open failed: \(storeURL.path)")
  }
  defer { sqlite3_close(db) }
  sqlite3_busy_timeout(db, sqliteBusyTimeoutMilliseconds)

  var statement: OpaquePointer?
  guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else {
    throw SQLiteProbeError(message: String(cString: sqlite3_errmsg(db)))
  }
  defer { sqlite3_finalize(statement) }

  var rows: [String] = []
  while true {
    let status = sqlite3_step(statement)
    if status == SQLITE_DONE { break }
    guard status == SQLITE_ROW else {
      throw SQLiteProbeError(message: String(cString: sqlite3_errmsg(db)))
    }
    if let value = sqlite3_column_text(statement, 0) {
      rows.append(String(cString: value))
    }
  }
  return rows.joined(separator: "\n")
}

private struct SQLiteProbeError: Error {
  let message: String
}
