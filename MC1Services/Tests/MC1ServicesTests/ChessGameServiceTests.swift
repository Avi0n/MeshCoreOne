import Foundation
@testable import MC1Services
import Testing

/// Delivers each player's DMs to the other, optionally dropping some.
private actor Wire {
  var services: [Data: ChessGameService] = [:]
  var names: [Data: String] = [:]
  private(set) var log: [(from: Data, text: String)] = []
  var drop: (@Sendable (String) -> Bool)?

  func register(_ key: Data, name: String, service: ChessGameService) {
    services[key] = service
    names[key] = name
  }

  func setDrop(_ rule: (@Sendable (String) -> Bool)?) {
    drop = rule
  }

  func deliver(from: Data, to: Data, text: String) async {
    log.append((from, text))
    if drop?(text) == true { return }
    guard let target = services[to] else { return }
    let name = names[from] ?? ""
    Task { await target.handle(text: text, fromKey: from, name: name) }
  }

  func texts(from key: Data) -> [String] {
    log.filter { $0.from == key }.map(\.text)
  }
}

@Suite("ChessGameService")
struct ChessGameServiceTests {
  private static let aliceKey = Data(repeating: 0xA1, count: 32)
  private static let bobKey = Data(repeating: 0xB0, count: 32)

  private static func makePair(
    ackTimeout: Duration = .milliseconds(200), stats: (GameStatsStore, GameStatsStore)? = nil
  ) async -> (ChessGameService, ChessGameService, Wire) {
    let wire = Wire()
    var config = ChessGameService.Config()
    config.ackTimeout = ackTimeout
    config.backoff = 1
    let alice = ChessGameService(config: config, storeURL: nil, stats: stats?.0, myName: { "Alice" }) { to, text in
      await wire.deliver(from: aliceKey, to: to, text: text)
      return nil
    }
    let bob = ChessGameService(config: config, storeURL: nil, stats: stats?.1, myName: { "Bob" }) { to, text in
      await wire.deliver(from: bobKey, to: to, text: text)
      return nil
    }
    await wire.register(aliceKey, name: "Alice", service: alice)
    await wire.register(bobKey, name: "Bob", service: bob)
    return (alice, bob, wire)
  }

  private static func waitFor(_ service: ChessGameService, _ condition: @Sendable (ChessGame?) -> Bool) async throws {
    for _ in 0..<200 {
      if condition(await service.game) { return }
      try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("condition not reached; game=\(String(describing: await service.game))")
  }

  private static func sq(_ name: String) -> ChessPosition.Square {
    let chars = Array(name)
    return .init(row: 8 - chars[1].wholeNumberValue!, col: Int(chars[0].asciiValue! - 97))
  }

  @Test("messages match the CoreChess wire format")
  func wireFormat() throws {
    let move = ChessPosition.Move(from: Self.sq("e1"), to: Self.sq("g1"), castle: .wK)
    #expect(CoreChessMessage.move(seq: 12, move: move, piece: "K", hash: "a1b2").text == "CC:MOV:12:7476KwK#a1b2")
    let parsed = try #require(CoreChessMessage(text: "CC:MOV:7:6444P#42f0"))
    guard case let .move(seq, m, piece, hash) = parsed else { Issue.record("not a move"); return }
    #expect(seq == 7 && m.from == Self.sq("e2") && m.to == Self.sq("e4") && piece == "P" && hash == "42f0")
    #expect(CoreChessMessage(text: "CC:RMV:Honza;AAAA") == .setup(name: "Honza", board: "AAAA"))
    #expect(CoreChessMessage(text: "CC:ACK:RES") == .ack("RES"))
    #expect(CoreChessMessage(text: "hello") == nil)
    #expect(CoreChessMessage.isNewer(1, than: 999))
    #expect(!CoreChessMessage.isNewer(5, than: 5))
  }

  @Test("board snapshot round trip")
  func board() throws {
    let position = try #require(ChessPosition(fen: "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R b Kq e3"))
    let text = CoreChessBoard.encode(position, senderColor: .black)
    #expect(text.count == 48)
    let decoded = try #require(CoreChessBoard.decode(text))
    #expect(decoded.senderColor == .black)
    #expect(decoded.position.board == position.board)
    #expect(decoded.position.turn == .black)
    #expect(decoded.position.enPassant == position.enPassant)
    #expect(decoded.position.castling == position.castling)
  }

  @Test("invite, accept, setup and a fool's mate")
  func fullGame() async throws {
    let stats = (GameStatsStore(storeURL: nil), GameStatsStore(storeURL: nil))
    let (alice, bob, wire) = await Self.makePair(stats: stats)
    await alice.invite(contactKey: Self.bobKey, name: "Bob")
    try await Self.waitFor(bob) { $0?.phase == .invited }
    await bob.acceptInvite()
    try await Self.waitFor(alice) { $0?.phase == .playing }
    try await Self.waitFor(bob) { $0?.phase == .playing && $0?.myColor == .black }

    for (player, from, to) in [(alice, "f2", "f3"), (bob, "e7", "e5"), (alice, "g2", "g4"), (bob, "d8", "h4")] {
      #expect(await player.play(from: Self.sq(from), to: Self.sq(to)))
      let other = player === alice ? bob : alice
      let plies = (await player.game?.ply) ?? 0
      try await Self.waitFor(other) { $0?.ply == plies }
      try await Self.waitFor(player) { $0?.awaitingAck == false }
    }
    #expect(await alice.game?.phase == .over)
    #expect(await bob.game?.ending == .checkmate(youWon: true))
    // Results are recorded off the game path; give both a moment.
    for _ in 0..<100 {
      let aliceDone = await stats.0.tallies(opponentKey: Self.bobKey)[GameStatsStore.chess] != nil
      let bobDone = await stats.1.tallies(opponentKey: Self.aliceKey)[GameStatsStore.chess] != nil
      if aliceDone, bobDone { break }
      try await Task.sleep(for: .milliseconds(10))
    }
    #expect(await stats.0.tallies(opponentKey: Self.bobKey)[GameStatsStore.chess] == GameTally(losses: 1))
    #expect(await stats.1.tallies(opponentKey: Self.aliceKey)[GameStatsStore.chess] == GameTally(wins: 1))
    #expect(await wire.texts(from: Self.aliceKey).first == "CC:INV:Alice")
    #expect(await wire.texts(from: Self.bobKey).contains("CC:ACK:RMV"))
  }

  @Test("lost move is retried and applied once")
  func retry() async throws {
    let (alice, bob, wire) = await Self.makePair()
    await alice.invite(contactKey: Self.bobKey, name: "Bob")
    try await Self.waitFor(bob) { $0?.phase == .invited }
    await bob.acceptInvite()
    try await Self.waitFor(bob) { $0?.phase == .playing }

    // First copy of the move and of its ACK are lost.
    let dropped = DropOnce()
    await wire.setDrop { text in text.hasPrefix("CC:MOV") || text.hasPrefix("CC:ACK:1") ? dropped.first(text) : false }
    #expect(await alice.play(from: Self.sq("e2"), to: Self.sq("e4")))
    try await Self.waitFor(bob) { $0?.ply == 1 }
    try await Self.waitFor(alice) { $0?.awaitingAck == false }
    #expect(await bob.game?.history == ["e2-e4"])
  }

  @Test("a move can be resent by hand before the retries run out")
  func manualResend() async throws {
    // A long timeout, so only the manual resend can deliver the move in time.
    let (alice, bob, wire) = await Self.makePair(ackTimeout: .seconds(30))
    await alice.invite(contactKey: Self.bobKey, name: "Bob")
    try await Self.waitFor(bob) { $0?.phase == .invited }
    await bob.acceptInvite()
    try await Self.waitFor(bob) { $0?.phase == .playing }
    try await Self.waitFor(alice) { $0?.phase == .playing }

    let dropped = DropOnce()
    await wire.setDrop { text in text.hasPrefix("CC:MOV") ? dropped.first(text) : false }
    #expect(await alice.play(from: Self.sq("e2"), to: Self.sq("e4")))
    #expect(await alice.game?.awaitingAck == true)
    #expect(await alice.game?.ackFailed == false)

    await alice.resendLastMove()
    try await Self.waitFor(bob) { $0?.ply == 1 }
    try await Self.waitFor(alice) { $0?.awaitingAck == false }
    #expect(await bob.game?.history == ["e2-e4"])

    // Once confirmed, a resend does nothing.
    let sent = await wire.texts(from: Self.aliceKey).count
    await alice.resendLastMove()
    #expect(await wire.texts(from: Self.aliceKey).count == sent)
  }

  @Test("an invite while playing is declined as busy")
  func busy() async throws {
    let (alice, bob, wire) = await Self.makePair()
    await alice.invite(contactKey: Self.bobKey, name: "Bob")
    try await Self.waitFor(bob) { $0?.phase == .invited }
    await bob.acceptInvite()
    try await Self.waitFor(bob) { $0?.phase == .playing }
    let carol = Data(repeating: 0xC0, count: 32)
    await bob.handle(text: "CC:INV:Carol", fromKey: carol, name: "Carol")
    #expect(await bob.game?.opponentName == "Alice")
    #expect(await wire.texts(from: Self.bobKey).contains("CC:DEC:busy"))
  }
}

/// Drops only the first occurrence of each text.
private final class DropOnce: @unchecked Sendable {
  private var seen: Set<String> = []
  private let lock = NSLock()

  func first(_ text: String) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return seen.insert(text).inserted
  }
}
