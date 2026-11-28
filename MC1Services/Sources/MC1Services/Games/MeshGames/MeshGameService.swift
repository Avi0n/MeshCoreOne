import Foundation

public enum MeshGameKind: String, Sendable, Codable, CaseIterable, Identifiable {
  case battleship = "BS"

  public var id: String {
    rawValue
  }

  public var symbol: String {
    switch self {
    case .battleship: "scope"
    }
  }
}

/// Per-game rules state.
public enum MeshGameBoard: Sendable, Equatable, Codable {
  case battleship(Battleship)

  var turn: Int {
    switch self {
    case let .battleship(g): g.turn
    }
  }

  var outcome: GameOutcome? {
    switch self {
    case let .battleship(g): g.outcome
    }
  }

  mutating func apply(_ move: String, by player: Int, me: Int) -> Bool {
    switch self {
    case var .battleship(g): defer { self = .battleship(g) }; return g.apply(move, by: player, me: me)
    }
  }

  static func new(_ kind: MeshGameKind) -> MeshGameBoard {
    switch kind {
    case .battleship: .battleship(Battleship(ships: Battleship.randomFleet()))
    }
  }

  /// Our commitment to a hidden secret (the battleship fleet); nil for open games.
  var commit: String? {
    switch self {
    case let .battleship(g): g.commit
    }
  }

  /// Our secret and salt, sent when the game is over: `<secret>;<salt>`.
  var revealPayload: String? {
    switch self {
    case let .battleship(g): g.layoutText + ";" + g.salt
    }
  }

  mutating func setOpponentCommit(_ commit: String) {
    switch self {
    case var .battleship(g): g.opponentCommit = commit; self = .battleship(g)
    }
  }

  /// Checks the opponent's revealed secret; returns false for open games.
  mutating func verifyReveal(secret: String, salt: String) -> Bool {
    switch self {
    case var .battleship(g): g.verifyReveal(layout: secret, salt: salt); self = .battleship(g); return true
    }
  }
}

/// One game against a contact.
public struct MeshGame: Sendable, Equatable, Codable, Identifiable {
  public enum Phase: String, Sendable, Codable {
    /// Battleship: we are placing our fleet; the invite goes out once it is set.
    case placing
    case inviting, invited, playing, over
  }

  /// `<kind>:<gameID>`
  public var id: String {
    kind.rawValue + ":" + gameID
  }

  public let kind: MeshGameKind
  public let gameID: String
  public var opponentKey: Data
  public var opponentName: String
  /// 0 = we invited and move first.
  public var me: Int
  public var phase: Phase
  public var board: MeshGameBoard
  public var moves: [String] = []
  public var seqOut = 0
  public var seqIn = 0
  public var awaitingAck = false
  public var ackFailed = false
  public var lastSentMove: String?
  /// How the game ended, once it is over.
  public var ending: GameEnding?
  public var warning: GameNotice?
  public var updatedAt = Date()
  /// Battleship: the opponent's revealed secret arrived.
  public var revealReceived = false
  /// Every game DM sent and received (optional so older saved games still decode).
  private var storedLog: [GameLogEntry]?

  public var log: [GameLogEntry] {
    get { storedLog ?? [] }
    set { storedLog = newValue }
  }

  public var isMyTurn: Bool {
    phase == .playing && board.turn == me && !awaitingAck
  }

  /// Our latest move on its way to the opponent; nil once they have moved.
  public var moveDelivery: GameDelivery? {
    log.moveDelivery(awaitingAck: awaitingAck, ackFailed: ackFailed)
  }
}

/// Wire format: `MG:<kind>:<gameID>:<TYPE>[:<payload>]` as plain-text DMs.
/// Types: `INV:<name>[|<commit>]`, `ACC:<name>[|<commit>]`, `DEC`, `MOV:<seq>:<move>`,
/// `ACK:<seq|TYPE>`, `RES`, `REV:<secret>;<salt>` (battleship reveal).
public struct MeshGameMessage: Sendable, Equatable {
  public static let prefix = "MG:"

  public let kind: MeshGameKind
  public let gameID: String
  public let type: String
  public let payload: String

  public var text: String {
    "MG:\(kind.rawValue):\(gameID):\(type)" + (payload.isEmpty ? "" : ":" + payload)
  }

  public init(kind: MeshGameKind, gameID: String, type: String, payload: String = "") {
    self.kind = kind
    self.gameID = gameID
    self.type = type
    self.payload = payload
  }

  public init?(text: String) {
    guard text.hasPrefix(Self.prefix) else { return nil }
    let parts = text.dropFirst(Self.prefix.count).split(separator: ":", maxSplits: 3, omittingEmptySubsequences: false)
    guard parts.count >= 3, let kind = MeshGameKind(rawValue: String(parts[0])), !parts[1].isEmpty else { return nil }
    self.kind = kind
    gameID = String(parts[1])
    type = String(parts[2])
    payload = parts.count > 3 ? String(parts[3]) : ""
  }
}

/// Runs every non-chess mesh game: invites, acknowledged moves with retries, resign,
/// and the commitment/reveal of hidden secrets (battleship). Several games can run at once.
public actor MeshGameService {
  public struct Config: Sendable {
    public var ackTimeout: Duration = .seconds(30)
    public var backoff = 1.5
    public init() {}
  }

  private struct Pending {
    let text: String
    var retriesLeft: Int
    var timeout: Duration
    var task: Task<Void, Never>?
    var attempt = 1
  }

  private let send: @Sendable (Data, String) async throws -> GameSendReceipt?
  private let myName: @Sendable () async -> String
  private let storeURL: URL?
  private let config: Config
  private let stats: GameStatsStore?
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "MeshGames")

  public private(set) var games: [String: MeshGame] = [:]
  /// Pending reliable sends, keyed by `gameKey|TYPE`.
  private var pending: [String: Pending] = [:]
  private var subscribers: [UUID: AsyncStream<[MeshGame]>.Continuation] = [:]

  public init(
    config: Config = Config(),
    storeURL: URL? = MeshGameService.defaultStoreURL,
    stats: GameStatsStore? = nil,
    myName: @escaping @Sendable () async -> String,
    send: @escaping @Sendable (Data, String) async throws -> GameSendReceipt?
  ) {
    self.config = config
    self.stats = stats
    self.storeURL = storeURL
    self.myName = myName
    self.send = send
    if let storeURL, let data = try? Data(contentsOf: storeURL),
       let saved = try? JSONDecoder().decode([Lossy<MeshGame>].self, from: data) {
      // Games of a kind this build no longer has are skipped, not fatal to the rest.
      games = Dictionary(saved.compactMap(\.value).map { ($0.id, $0) }) { first, _ in first }
    }
  }

  public static var defaultStoreURL: URL {
    URL.applicationSupportDirectory.appending(path: "Games/mesh-games.json")
  }

  public func updates() -> AsyncStream<[MeshGame]> {
    let id = UUID()
    let (stream, continuation) = AsyncStream<[MeshGame]>.makeStream()
    subscribers[id] = continuation
    continuation.yield(sortedGames)
    continuation.onTermination = { [weak self] _ in
      Task { await self?.unsubscribe(id) }
    }
    return stream
  }

  private func unsubscribe(_ id: UUID) {
    subscribers[id] = nil
  }

  private var sortedGames: [MeshGame] {
    games.values.sorted { $0.updatedAt > $1.updatedAt }
  }

  private func store(_ game: MeshGame) {
    var game = game
    game.updatedAt = Date()
    games[game.id] = game
    publish()
  }

  private func publish() {
    if let storeURL {
      try? FileManager.default.createDirectory(at: storeURL.deletingLastPathComponent(), withIntermediateDirectories: true)
      if let data = try? JSONEncoder().encode(Array(games.values)) {
        try? data.write(to: storeURL, options: .atomic)
      }
    }
    let snapshot = sortedGames
    for continuation in subscribers.values {
      continuation.yield(snapshot)
    }
  }

  // MARK: - User actions

  /// Starts a game with a contact. Battleship starts in `.placing`: the invite (which
  /// commits to our fleet) goes out from `sendInvite(_:fleet:)` once the fleet is set.
  @discardableResult
  public func invite(_ kind: MeshGameKind, contactKey: Data, name: String) async -> String {
    let gameID = String(format: "%04x", UInt16.random(in: 1...UInt16.max))
    let game = MeshGame(
      kind: kind, gameID: gameID, opponentKey: contactKey, opponentName: name,
      me: 0, phase: kind == .battleship ? .placing : .inviting, board: .new(kind)
    )
    store(game)
    if game.phase == .inviting {
      await sendReliably(game, type: "INV", payload: namePayload(for: game), retries: 3)
    }
    return game.id
  }

  /// Sends the invite of a game in `.placing` with our chosen fleet (nil keeps the current one).
  public func sendInvite(_ id: String, fleet: [Battleship.Ship]? = nil) async {
    guard var game = games[id], game.phase == .placing else { return }
    guard Self.useFleet(fleet, in: &game) else { return }
    game.phase = .inviting
    store(game)
    await sendReliably(game, type: "INV", payload: namePayload(for: game), retries: 3)
  }

  /// Accepts an invite; for battleship, `fleet` replaces the random one (nil keeps it).
  public func accept(_ id: String, fleet: [Battleship.Ship]? = nil) async {
    guard var game = games[id], game.phase == .invited else { return }
    guard Self.useFleet(fleet, in: &game) else { return }
    game.phase = .playing
    store(game)
    await sendReliably(game, type: "ACC", payload: namePayload(for: game), retries: 3)
  }

  /// Puts a chosen battleship fleet on the board before it is committed to.
  private static func useFleet(_ fleet: [Battleship.Ship]?, in game: inout MeshGame) -> Bool {
    guard let fleet else { return true }
    guard case let .battleship(sea) = game.board, Battleship.valid(fleet) else { return false }
    var placed = Battleship(ships: fleet)
    placed.opponentCommit = sea.opponentCommit
    game.board = .battleship(placed)
    return true
  }

  public func decline(_ id: String) async {
    guard let game = games[id] else { return }
    await transmit(MeshGameMessage(kind: game.kind, gameID: game.gameID, type: "DEC"), to: game.opponentKey)
    games[id] = nil
    publish()
  }

  /// Plays our move (wire text, e.g. `4`, `h8`, `c3-d4`, `F:B5`). Returns false if not allowed.
  @discardableResult
  public func play(_ id: String, move: String) async -> Bool {
    guard var game = games[id], game.isMyTurn else { return false }
    guard game.board.apply(move, by: game.me, me: game.me) else { return false }
    game.moves.append(move)
    game.seqOut = game.seqOut % 999 + 1
    game.awaitingAck = true
    game.ackFailed = false
    game.lastSentMove = move
    game.warning = nil
    finishIfOver(&game)
    store(game)
    await sendReliably(game, type: "MOV", payload: "\(game.seqOut):\(move)", retries: 4)
    if game.phase == .over { await revealSecret(game) }
    return true
  }

  /// Sends the unacknowledged move again (same sequence number, so it is applied once).
  /// Allowed while automatic retries are still running too; it restarts their schedule.
  public func resendLastMove(_ id: String) async {
    guard var game = games[id], game.awaitingAck || game.ackFailed, let move = game.lastSentMove else { return }
    game.ackFailed = false
    game.awaitingAck = true
    store(game)
    await sendReliably(game, type: "MOV", payload: "\(game.seqOut):\(move)", retries: 4)
  }

  public func resign(_ id: String) async {
    guard var game = games[id], game.phase == .playing else { return }
    game.phase = .over
    game.ending = .youResigned
    record(.loss, game)
    store(game)
    await sendReliably(game, type: "RES", retries: 3)
    await revealSecret(game)
  }

  public func remove(_ id: String) async {
    guard let game = games[id] else { return }
    if game.phase == .playing { await resign(id) }
    if game.phase == .invited { await decline(id) }
    for key in pending.keys where key.hasPrefix(id + "|") {
      pending[key]?.task?.cancel()
      pending[key] = nil
    }
    games[id] = nil
    publish()
  }

  // MARK: - Incoming

  /// Handles an `MG:` DM. Returns true if it was a game message.
  @discardableResult
  public func handle(text: String, fromKey: Data, name: String, rx: GameRxInfo? = nil) async -> Bool {
    guard let message = MeshGameMessage(text: text) else { return text.hasPrefix(MeshGameMessage.prefix) }
    let id = message.kind.rawValue + ":" + message.gameID

    if message.type == "INV" {
      await handleInvite(message, id: id, fromKey: fromKey, name: name, rx: rx)
      return true
    }
    guard let known = games[id], Self.sameNode(known.opponentKey, fromKey) else { return true }
    games[id]?.log.appendCapped(.received(message.type, text, rx: rx))
    publish()
    guard var game = games[id] else { return true }

    switch message.type {
    case "ACC":
      guard game.phase == .inviting else {
        await transmit(reply(game, "ACK", "ACC"), to: fromKey)
        return true
      }
      cancelPending(id, "INV")
      game.phase = .playing
      setOpponentCommit(&game, from: message.payload)
      store(game)
      await transmit(reply(game, "ACK", "ACC"), to: fromKey)
      // Nothing below may use `game`: other messages can run during the await.

    case "DEC":
      cancelPending(id, "INV")
      game.phase = .over
      game.ending = .opponentDeclined
      store(game)

    case "MOV":
      // Any move from them means our accept arrived.
      cancelPending(id, "ACC")
      let parts = message.payload.split(separator: ":", maxSplits: 1)
      guard parts.count == 2, let seq = Int(parts[0]) else { return true }
      let move = String(parts[1])
      let ack = reply(game, "ACK", String(seq))
      guard game.phase == .playing, CoreChessMessage.isNewer(seq, than: game.seqIn) else {
        await transmit(ack, to: fromKey)
        return true
      }
      if game.awaitingAck {
        cancelPending(id, "MOV")
        game.awaitingAck = false
        game.ackFailed = false
      }
      guard game.board.apply(move, by: 1 - game.me, me: game.me) else {
        game.warning = .invalidMove(move)
        store(game)
        await transmit(ack, to: fromKey)
        return true
      }
      game.seqIn = seq
      game.moves.append(move)
      game.warning = nil
      finishIfOver(&game)
      store(game)
      await transmit(ack, to: fromKey)
      if game.phase == .over { await revealSecret(game) }

    case "ACK":
      if let seq = Int(message.payload) {
        guard seq == game.seqOut, game.awaitingAck else { return true }
        cancelPending(id, "MOV")
        game.awaitingAck = false
        game.ackFailed = false
        store(game)
      } else {
        cancelPending(id, message.payload)
      }

    case "RES":
      let ack = reply(game, "ACK", "RES")
      guard game.phase == .playing else {
        await transmit(ack, to: fromKey)
        return true
      }
      cancelAllPending(id)
      game.phase = .over
      game.ending = .opponentResigned
      record(.win, game)
      store(game)
      await transmit(ack, to: fromKey)
      await revealSecret(game)

    case "REV":
      let ack = reply(game, "ACK", "REV")
      let parts = message.payload.split(separator: ";", maxSplits: 1)
      if !game.revealReceived, parts.count == 2,
         game.board.verifyReveal(secret: String(parts[0]), salt: String(parts[1])) {
        game.revealReceived = true
        store(game)
      }
      await transmit(ack, to: fromKey)

    default:
      break
    }
    return true
  }

  private func handleInvite(_ message: MeshGameMessage, id: String, fromKey: Data, name: String, rx: GameRxInfo?) async {
    if let existing = games[id] {
      games[id]?.log.appendCapped(.received(message.type, message.text, rx: rx))
      publish()
      // Our accept was lost: answer again.
      if existing.phase == .playing, existing.me == 1, existing.moves.isEmpty {
        await transmit(reply(existing, "ACC", namePayload(for: existing)), to: fromKey)
      }
      return
    }
    let parts = message.payload.split(separator: "|", maxSplits: 1)
    let inviterName = name.isEmpty ? String(parts.first ?? "Player") : name
    var game = MeshGame(
      kind: message.kind, gameID: message.gameID, opponentKey: fromKey, opponentName: inviterName,
      me: 1, phase: .invited, board: .new(message.kind)
    )
    game.log.appendCapped(.received(message.type, message.text, rx: rx))
    setOpponentCommit(&game, from: message.payload)
    store(game)
  }

  // MARK: - Helpers

  private func namePayload(for game: MeshGame) async -> String {
    var payload = await myName().replacingOccurrences(of: "|", with: "/").replacingOccurrences(of: ":", with: " ")
    if let commit = game.board.commit { payload += "|" + commit }
    return payload
  }

  private func setOpponentCommit(_ game: inout MeshGame, from payload: String) {
    guard game.board.commit != nil, let bar = payload.lastIndex(of: "|") else { return }
    game.board.setOpponentCommit(String(payload[payload.index(after: bar)...]))
  }

  private func finishIfOver(_ game: inout MeshGame) {
    guard let outcome = game.board.outcome else { return }
    game.phase = .over
    switch outcome {
    case .draw:
      game.ending = .draw
      record(.draw, game)
    case let .win(player):
      game.ending = player == game.me ? .youWon : .opponentWon
      record(player == game.me ? .win : .loss, game)
    }
  }

  /// Adds a finished game to the head-to-head record. Call once, where the game ends.
  private func record(_ result: GameResult, _ game: MeshGame) {
    guard let stats else { return }
    let kind = game.kind.rawValue
    let opponent = game.opponentKey
    Task { await stats.record(result, game: kind, opponentKey: opponent) }
  }

  /// Reveals our hidden secret once the game is over, so the opponent can check our answers.
  private func revealSecret(_ game: MeshGame) async {
    guard let payload = game.board.revealPayload else { return }
    await sendReliably(game, type: "REV", payload: payload, retries: 3)
  }

  private func reply(_ game: MeshGame, _ type: String, _ payload: String = "") -> MeshGameMessage {
    MeshGameMessage(kind: game.kind, gameID: game.gameID, type: type, payload: payload)
  }

  private static func sameNode(_ a: Data, _ b: Data) -> Bool {
    let length = min(6, a.count, b.count)
    return length > 0 && a.prefix(length) == b.prefix(length)
  }

  private func transmit(_ message: MeshGameMessage, to key: Data, attempt: Int = 1) async {
    var receipt: GameSendReceipt?
    var failure: Error?
    do {
      receipt = try await send(key, message.text)
    } catch {
      failure = error
      logger.warning("send \(message.type) failed: \(error.localizedDescription)")
    }
    let id = message.kind.rawValue + ":" + message.gameID
    guard games[id] != nil else { return }
    let entry = GameLogEntry.sent(message.type, message.text, attempt: attempt, receipt: receipt, error: failure)
    games[id]?.log.appendCapped(entry)
    publish()
    if let radioAck = receipt?.radioAck {
      Task { [weak self] in
        let tripTime = await radioAck.value
        await self?.radioAcknowledged(id, entry: entry.id, tripTime: tripTime)
      }
    }
  }

  private func radioAcknowledged(_ id: String, entry: UUID, tripTime: UInt32?) {
    guard games[id] != nil else { return }
    games[id]?.log.setRadioAck(entry, tripTime: tripTime)
    publish()
  }

  private func sendReliably(_ game: MeshGame, type: String, payload: String = "", retries: Int) async {
    let key = game.id + "|" + type
    pending[key]?.task?.cancel()
    let message = reply(game, type, payload)
    pending[key] = Pending(text: message.text, retriesLeft: retries, timeout: config.ackTimeout, task: nil)
    await transmit(message, to: game.opponentKey)
    scheduleRetry(key, gameID: game.id)
  }

  private func scheduleRetry(_ key: String, gameID: String) {
    guard let timeout = pending[key]?.timeout else { return }
    pending[key]?.task = Task { [weak self] in
      try? await Task.sleep(for: timeout)
      guard !Task.isCancelled else { return }
      await self?.retry(key, gameID: gameID)
    }
  }

  private func retry(_ key: String, gameID: String) async {
    guard var entry = pending[key], let game = games[gameID] else { return }
    guard entry.retriesLeft > 0 else {
      pending[key] = nil
      giveUp(key, gameID: gameID)
      return
    }
    entry.retriesLeft -= 1
    entry.attempt += 1
    // Duration has `*=` only for Int; the backoff is a Double.
    // swiftlint:disable:next shorthand_operator
    entry.timeout = entry.timeout * config.backoff
    pending[key] = entry
    if let message = MeshGameMessage(text: entry.text) {
      await transmit(message, to: game.opponentKey, attempt: entry.attempt)
    }
    scheduleRetry(key, gameID: gameID)
  }

  private func giveUp(_ key: String, gameID: String) {
    guard var game = games[gameID] else { return }
    switch key.split(separator: "|").last {
    case "MOV":
      game.ackFailed = true
      game.warning = .moveNotAcknowledged
    case "INV":
      game.phase = .over
      game.ending = .inviteUnanswered
    case "ACC":
      game.warning = .opponentNotConfirmed
    default:
      return
    }
    store(game)
  }

  private func cancelPending(_ gameID: String, _ type: String) {
    let key = gameID + "|" + type
    pending[key]?.task?.cancel()
    pending[key] = nil
  }

  private func cancelAllPending(_ gameID: String) {
    for key in pending.keys where key.hasPrefix(gameID + "|") {
      pending[key]?.task?.cancel()
      pending[key] = nil
    }
  }
}

/// Decodes an element, or nil if it no longer decodes, so one stale entry
/// does not throw away a whole saved array.
struct Lossy<Value: Decodable>: Decodable {
  let value: Value?

  init(from decoder: Decoder) throws {
    value = try? Value(from: decoder)
  }
}
