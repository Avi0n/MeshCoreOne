import Foundation
@testable import MC1Services
import Testing

private actor GameWire {
  var services: [Data: MeshGameService] = [:]
  var names: [Data: String] = [:]
  private(set) var log: [(from: Data, text: String)] = []

  func register(_ key: Data, name: String, service: MeshGameService?) {
    services[key] = service
    names[key] = name
  }

  func deliver(from: Data, to: Data, text: String) {
    log.append((from, text))
    guard let target = services[to] else { return }
    let name = names[from] ?? ""
    Task { await target.handle(text: text, fromKey: from, name: name) }
  }

  func count(from key: Data, containing part: String) -> Int {
    log.filter { $0.from == key && $0.text.contains(part) }.count
  }
}

@Suite("MeshGameService")
struct MeshGameServiceTests {
  private static let aKey = Data(repeating: 0xA1, count: 32)
  private static let bKey = Data(repeating: 0xB0, count: 32)

  private static func makePair() async -> (MeshGameService, MeshGameService, GameWire) {
    let wire = GameWire()
    var config = MeshGameService.Config()
    config.ackTimeout = .milliseconds(300)
    let a = MeshGameService(config: config, storeURL: nil, myName: { "Alice" }) { to, text in
      await wire.deliver(from: aKey, to: to, text: text)
      return nil
    }
    let b = MeshGameService(config: config, storeURL: nil, myName: { "Bob" }) { to, text in
      await wire.deliver(from: bKey, to: to, text: text)
      return nil
    }
    await wire.register(aKey, name: "Alice", service: a)
    await wire.register(bKey, name: "Bob", service: b)
    return (a, b, wire)
  }

  private static func waitFor(_ service: MeshGameService, _ id: String, _ condition: @Sendable (MeshGame?) -> Bool) async throws {
    for _ in 0..<300 {
      if await condition(service.games[id]) { return }
      try await Task.sleep(for: .milliseconds(10))
    }
    await Issue.record("condition not reached: \(String(describing: service.games[id]))")
  }

  /// Alice invites Bob to battleship with random fleets; returns once both sides play.
  private static func startBattleship(_ a: MeshGameService, _ b: MeshGameService) async throws -> String {
    let id = await a.invite(.battleship, contactKey: bKey, name: "Bob")
    await a.sendInvite(id)
    try await waitFor(b, id) { $0?.phase == .invited }
    await b.accept(id)
    try await waitFor(a, id) { $0?.phase == .playing }
    try await waitFor(b, id) { $0?.phase == .playing }
    return id
  }

  /// Alice's opening shot at A1.
  private static func openingShot(_ a: MeshGameService, _ id: String) async throws -> String {
    guard case let .battleship(sea)? = await a.games[id]?.board else {
      Issue.record("no sea")
      return ""
    }
    return try #require(sea.moveText(shootingAt: GridCell(row: 0, col: 0), answering: nil))
  }

  @Test
  func `message codec`() {
    let message = MeshGameMessage(kind: .battleship, gameID: "1a2b", type: "MOV", payload: "7:R:H;F:B3")
    #expect(message.text == "MG:BS:1a2b:MOV:7:R:H;F:B3")
    #expect(MeshGameMessage(text: message.text) == message)
    #expect(MeshGameMessage(text: "MG:XX:1:MOV") == nil)
  }

  @Test
  func `saved games that no longer decode are skipped, the rest load`() async throws {
    let url = URL.temporaryDirectory.appending(path: "mesh-games-\(UUID().uuidString).json")
    defer { try? FileManager.default.removeItem(at: url) }
    let wire = GameWire()
    let a = MeshGameService(storeURL: url, myName: { "Alice" }) { to, text in
      await wire.deliver(from: Self.aKey, to: to, text: text)
      return nil
    }
    let id = await a.invite(.battleship, contactKey: Self.bKey, name: "Bob")

    // Add entries from removed game kinds next to the saved one.
    var saved = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [Any])
    saved.append(["kind": "C4", "gameID": "dead"])
    saved.append(["kind": "LG", "gameID": "beef"])
    try JSONSerialization.data(withJSONObject: saved).write(to: url)

    let reloaded = MeshGameService(storeURL: url, myName: { "Alice" }) { _, _ in nil }
    #expect(await reloaded.games.keys.sorted() == [id])
  }

  @Test
  func `the log records sends and receipts, and tracks move delivery`() async throws {
    let (a, b, _) = await Self.makePair()
    let id = try await Self.startBattleship(a, b)
    #expect(await a.games[id]?.moveDelivery == nil)

    let shot = try await Self.openingShot(a, id)
    #expect(await a.play(id, move: shot))
    try await Self.waitFor(a, id) { $0?.moveDelivery == .confirmed }
    try await Self.waitFor(b, id) { $0?.isMyTurn == true && $0?.moveDelivery == nil }

    let log = try #require(await a.games[id]?.log)
    #expect(log.contains { $0.direction == .sent && $0.type == "INV" })
    #expect(log.contains { $0.direction == .sent && $0.type == "MOV" && $0.text.hasSuffix(":" + shot) })
    #expect(log.contains { $0.direction == .received && $0.type == "ACK" })
    let bLog = try #require(await b.games[id]?.log)
    #expect(bLog.first?.type == "INV" && bLog.first?.direction == .received)
  }

  @Test
  func `an unanswered move shows its retries`() async throws {
    let (a, b, wire) = await Self.makePair()
    let id = try await Self.startBattleship(a, b)
    await wire.register(Self.bKey, name: "Bob", service: nil)

    #expect(try await a.play(id, move: Self.openingShot(a, id)))
    #expect(await a.games[id]?.moveDelivery == .sending(attempt: 1, flood: nil))
    try await Self.waitFor(a, id) {
      if case let .sending(attempt, _)? = $0?.moveDelivery { return attempt >= 2 }
      return false
    }
  }

  @Test
  func `a move can be resent by hand before the retries run out`() async throws {
    let (a, b, wire) = await Self.makePair()
    let id = try await Self.startBattleship(a, b)

    // Bob is out of reach for the first send.
    await wire.register(Self.bKey, name: "Bob", service: nil)
    let shot = try await Self.openingShot(a, id)
    #expect(await a.play(id, move: shot))
    #expect(await a.games[id]?.ackFailed == false)
    await wire.register(Self.bKey, name: "Bob", service: b)

    await a.resendLastMove(id)
    try await Self.waitFor(b, id) { $0?.moves == [shot] }
    try await Self.waitFor(a, id) { $0?.awaitingAck == false }
    #expect(await wire.count(from: Self.aKey, containing: ":MOV:") >= 2)
  }

  @Test
  func `finished games add to the head-to-head record`() async throws {
    let wire = GameWire()
    let aStats = GameStatsStore(storeURL: nil)
    let bStats = GameStatsStore(storeURL: nil)
    let a = MeshGameService(storeURL: nil, stats: aStats, myName: { "Alice" }) { to, text in
      await wire.deliver(from: Self.aKey, to: to, text: text)
      return nil
    }
    let b = MeshGameService(storeURL: nil, stats: bStats, myName: { "Bob" }) { to, text in
      await wire.deliver(from: Self.bKey, to: to, text: text)
      return nil
    }
    await wire.register(Self.aKey, name: "Alice", service: a)
    await wire.register(Self.bKey, name: "Bob", service: b)

    let id = try await Self.startBattleship(a, b)
    await b.resign(id)
    try await Self.waitFor(a, id) { $0?.phase == .over }
    #expect(await a.games[id]?.ending == .opponentResigned)
    #expect(await b.games[id]?.ending == .youResigned)

    try await Self.waitForTally(aStats, Self.bKey) { $0["BS"] == GameTally(wins: 1) }
    try await Self.waitForTally(bStats, Self.aKey) { $0["BS"] == GameTally(losses: 1) }
    // A declined invite is not a result.
    let declined = await a.invite(.battleship, contactKey: Self.bKey, name: "Bob")
    await a.sendInvite(declined)
    try await Self.waitFor(b, declined) { $0?.phase == .invited }
    await b.decline(declined)
    try await Self.waitFor(a, declined) { $0?.phase == .over }
    #expect(await a.games[declined]?.ending == .opponentDeclined)
    #expect(await aStats.tallies(opponentKey: Self.bKey)["BS"]?.played == 1)
  }

  private static func waitForTally(
    _ stats: GameStatsStore, _ key: Data, _ condition: @Sendable ([String: GameTally]) -> Bool
  ) async throws {
    for _ in 0..<300 {
      if await condition(stats.tallies(opponentKey: key)) { return }
      try await Task.sleep(for: .milliseconds(10))
    }
    await Issue.record("tally not reached: \(stats.tallies(opponentKey: key))")
  }

  @Test
  func `battleship fleets placed by hand are the ones committed to`() async throws {
    let (a, b, wire) = await Self.makePair()
    let id = await a.invite(.battleship, contactKey: Self.bKey, name: "Bob")
    // Nothing is sent while the inviter places the fleet.
    #expect(await wire.count(from: Self.aKey, containing: ":INV:") == 0)

    let aFleet = Battleship.randomFleet()
    let bFleet = Battleship.randomFleet()
    // An incomplete fleet is refused and nothing is sent.
    await a.sendInvite(id, fleet: Array(aFleet.dropLast()))
    #expect(await a.games[id]?.phase == .placing)
    #expect(await wire.count(from: Self.aKey, containing: ":INV:") == 0)

    await a.sendInvite(id, fleet: aFleet)
    try await Self.waitFor(b, id) { $0?.phase == .invited }
    await b.accept(id, fleet: bFleet)
    try await Self.waitFor(a, id) { $0?.phase == .playing }

    guard case let .battleship(aSea)? = await a.games[id]?.board,
          case let .battleship(bSea)? = await b.games[id]?.board
    else { Issue.record("no sea"); return }
    #expect(aSea.ships == aFleet && bSea.ships == bFleet)
    // Each side holds the commitment to the other's chosen fleet.
    #expect(aSea.opponentCommit == bSea.commit)
    #expect(bSea.opponentCommit == aSea.commit)
  }

  @Test
  func `battleship over the wire ends with honest reveals`() async throws {
    let (a, b, _) = await Self.makePair()
    let id = await a.invite(.battleship, contactKey: Self.bKey, name: "Bob")
    #expect(await a.games[id]?.phase == .placing)
    await a.sendInvite(id)
    try await Self.waitFor(b, id) { $0?.phase == .invited }
    await b.accept(id)
    try await Self.waitFor(a, id) { $0?.phase == .playing }

    guard case let .battleship(bSea)? = await b.games[id]?.board else { Issue.record("no sea"); return }
    var aTargets = bSea.ships.flatMap(\.cells).makeIterator()
    var bTargets = (0..<100).map { GridCell(row: $0 / 10, col: $0 % 10) }.makeIterator()

    var turn = 0
    while await a.games[id]?.phase == .playing {
      let service = turn == 0 ? a : b
      guard case let .battleship(sea)? = await service.games[id]?.board else { break }
      let text: String
      if let shot = sea.lastOpponentShot, let final = sea.losingAnswer(to: shot) {
        text = final
      } else {
        var target = turn == 0 ? aTargets.next()! : bTargets.next()!
        while sea.outgoing[target] != nil {
          target = turn == 0 ? aTargets.next()! : bTargets.next()!
        }
        text = try #require(sea.moveText(shootingAt: target, answering: sea.lastOpponentShot))
      }
      let count = await (service.games[id]?.moves.count) ?? 0
      #expect(await service.play(id, move: text))
      let other = turn == 0 ? b : a
      try await Self.waitFor(other, id) { $0?.moves.count == count + 1 }
      turn = 1 - turn
    }
    try await Self.waitFor(a, id) { $0?.revealReceived == true }
    try await Self.waitFor(b, id) { $0?.revealReceived == true }
    guard case let .battleship(finalA)? = await a.games[id]?.board,
          case let .battleship(finalB)? = await b.games[id]?.board else { return }
    #expect(finalA.verdict == .honest)
    #expect(finalB.verdict == .honest)
    #expect(await a.games[id]?.ending == .youWon)
    #expect(await b.games[id]?.ending == .opponentWon)
  }
}
