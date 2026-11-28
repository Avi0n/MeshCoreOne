import Foundation

/// One chess game against a contact, as the UI sees it.
public struct ChessGame: Sendable, Equatable, Codable {
  public enum Phase: String, Sendable, Codable {
    /// We sent an invite and wait for an answer.
    case inviting
    /// Someone invited us.
    case invited
    /// We accepted; the inviter sends the board next.
    case waitingForSetup
    case playing
    case over
  }

  public var phase: Phase
  public var opponentKey: Data
  public var opponentName: String
  public var myColor: ChessPosition.Color = .white
  public var position: ChessPosition = .start
  /// Moves in coordinate notation, e.g. `e2-e4`.
  public var history: [String] = []
  public var lastMove: ChessPosition.Move?
  public var ply = 0
  public var seqOut = 0
  public var seqIn = -1
  /// Our last move has not been acknowledged yet.
  public var awaitingAck = false
  /// Our last move was never acknowledged; offer a resend.
  public var ackFailed = false
  public var drawOfferedByMe = false
  public var drawOfferedByOpponent = false
  /// How the game ended, once it is over.
  public var ending: GameEnding?
  public var warning: GameNotice?
  public var repetitions: [String: Int] = [:]
  public var lastSentMoveText: String?
  public var setupKey: String?
  /// Every chess DM sent and received (optional so older saved games still decode).
  private var storedLog: [GameLogEntry]?

  public var log: [GameLogEntry] {
    get { storedLog ?? [] }
    set { storedLog = newValue }
  }

  public var isMyTurn: Bool { phase == .playing && position.turn == myColor }

  /// Our latest move on its way to the opponent; nil once they have moved.
  public var moveDelivery: GameDelivery? { log.moveDelivery(awaitingAck: awaitingAck, ackFailed: ackFailed) }
}

/// Plays CoreChess-compatible chess over DMs: invites, acknowledged moves with retries,
/// draws, resignation and board resync. One game at a time, like CoreChess.
public actor ChessGameService {
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
    let seq: Int?
    var attempt = 1
  }

  private let send: @Sendable (Data, String) async throws -> GameSendReceipt?
  private let myName: @Sendable () async -> String
  private let storeURL: URL?
  private let stats: GameStatsStore?
  private let config: Config
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "Chess")

  public private(set) var game: ChessGame?
  private var pending: [String: Pending] = [:]
  private var subscribers: [UUID: AsyncStream<ChessGame?>.Continuation] = [:]
  private var lastResyncRequest: Date = .distantPast
  private var lastSyncSent: Date = .distantPast

  public init(
    config: Config = Config(),
    storeURL: URL? = ChessGameService.defaultStoreURL,
    stats: GameStatsStore? = nil,
    myName: @escaping @Sendable () async -> String,
    send: @escaping @Sendable (Data, String) async throws -> GameSendReceipt?
  ) {
    self.config = config
    self.stats = stats
    self.storeURL = storeURL
    self.myName = myName
    self.send = send
    if let storeURL, let data = try? Data(contentsOf: storeURL) {
      game = try? JSONDecoder().decode(ChessGame.self, from: data)
    }
  }

  public static var defaultStoreURL: URL {
    URL.applicationSupportDirectory.appending(path: "Games/chess.json")
  }

  public func updates() -> AsyncStream<ChessGame?> {
    let id = UUID()
    let (stream, continuation) = AsyncStream<ChessGame?>.makeStream()
    subscribers[id] = continuation
    continuation.yield(game)
    continuation.onTermination = { [weak self] _ in
      Task { await self?.unsubscribe(id) }
    }
    return stream
  }

  private func unsubscribe(_ id: UUID) {
    subscribers[id] = nil
  }

  private func publish() {
    if let storeURL {
      try? FileManager.default.createDirectory(at: storeURL.deletingLastPathComponent(), withIntermediateDirectories: true)
      if let game, let data = try? JSONEncoder().encode(game) {
        try? data.write(to: storeURL, options: .atomic)
      } else {
        try? FileManager.default.removeItem(at: storeURL)
      }
    }
    for continuation in subscribers.values {
      continuation.yield(game)
    }
  }

  // MARK: - User actions

  public func invite(contactKey: Data, name: String) async {
    if let game, game.phase == .playing { return }
    cancelAllPending()
    game = ChessGame(phase: .inviting, opponentKey: contactKey, opponentName: name)
    publish()
    await sendReliably(.invite(name: await myName()), type: "INV", retries: 3)
  }

  public func acceptInvite() async {
    guard var current = game, current.phase == .invited else { return }
    current.phase = .waitingForSetup
    game = current
    publish()
    await sendReliably(.accept(name: await myName()), type: "ACC", retries: 3)
  }

  public func declineInvite() async {
    guard let current = game, current.phase == .invited else { return }
    await transmit(.decline(busy: false), to: current.opponentKey)
    game = nil
    publish()
  }

  /// Plays our move. Returns false if it is not legal or not our turn.
  @discardableResult
  public func play(from: ChessPosition.Square, to: ChessPosition.Square) async -> Bool {
    guard var current = game, current.isMyTurn,
          let move = current.position.legalMove(from: from, to: to),
          let piece = current.position[from]
    else { return false }
    let opponentColor = current.myColor.opposite
    current.position = current.position.applying(move)
    current.lastMove = move
    current.history.append(move.name)
    current.ply += 1
    current.seqOut = current.seqOut % 999 + 1
    current.awaitingAck = true
    current.ackFailed = false
    current.drawOfferedByOpponent = false
    let message = CoreChessMessage.move(
      seq: current.seqOut, move: move, piece: piece,
      hash: current.position.coreChessHash(turnColor: opponentColor)
    )
    current.lastSentMoveText = message.text
    recordAndCheckEnd(&current)
    game = current
    publish()
    await sendReliably(message, type: "MOV", retries: 4, seq: current.seqOut)
    return true
  }

  /// Sends the unacknowledged move again (same sequence number, so it is applied once).
  /// Allowed while automatic retries are still running too; it restarts their schedule.
  public func resendLastMove() async {
    guard var current = game, current.awaitingAck || current.ackFailed, let text = current.lastSentMoveText,
          let message = CoreChessMessage(text: text)
    else { return }
    current.ackFailed = false
    current.awaitingAck = true
    game = current
    publish()
    await sendReliably(message, type: "MOV", retries: 4, seq: current.seqOut)
  }

  public func resign() async {
    guard var current = game, current.phase == .playing else { return }
    current.phase = .over
    current.ending = .youResigned
    record(.loss, current)
    game = current
    publish()
    await sendReliably(.resign(reset: false), type: "RES", retries: 4)
  }

  public func offerDraw() async {
    guard var current = game, current.phase == .playing, !current.drawOfferedByMe else { return }
    current.drawOfferedByMe = true
    game = current
    publish()
    await sendReliably(.draw("offer"), type: "DRW", retries: 2)
  }

  public func answerDraw(accept: Bool) async {
    guard var current = game, current.phase == .playing, current.drawOfferedByOpponent else { return }
    current.drawOfferedByOpponent = false
    if accept {
      current.phase = .over
      current.ending = .drawAgreed
      record(.draw, current)
    }
    game = current
    publish()
    await sendReliably(.draw(accept ? "accept" : "decline"), type: "DRW", retries: accept ? 3 : 2)
  }

  /// Leaves the current game. A running game is reset for the opponent too.
  public func close() async {
    guard let current = game else { return }
    if current.phase == .playing {
      await sendReliably(.resign(reset: true), type: "RES", retries: 3)
    } else if current.phase == .invited {
      await transmit(.decline(busy: false), to: current.opponentKey)
    }
    if current.phase != .playing { cancelAllPending() }
    game = nil
    publish()
  }

  // MARK: - Incoming

  /// Handles a `CC:` DM. Returns true if it was a chess message.
  @discardableResult
  public func handle(text: String, fromKey: Data, name: String, rx: GameRxInfo? = nil) async -> Bool {
    guard let message = CoreChessMessage(text: text) else { return text.hasPrefix(CoreChessMessage.prefix) }
    let fromOpponent = game.map { Self.sameNode($0.opponentKey, fromKey) } ?? false

    if case let .invite(inviterName) = message {
      await handleInvite(from: fromKey, name: name.isEmpty ? inviterName : name, fromOpponent: fromOpponent)
      if let current = game, Self.sameNode(current.opponentKey, fromKey) {
        logReceived(text, rx: rx)
      }
      return true
    }
    guard fromOpponent, game != nil else {
      logger.info("ignoring \(text.prefix(12)) from a node that is not our opponent")
      return true
    }
    logReceived(text, rx: rx)
    guard var current = game else { return true }

    switch message {
    case .invite:
      break
    case .accept:
      guard current.phase == .inviting else { return true }
      cancelPending("INV")
      current.phase = .playing
      current.myColor = .white
      current.position = .start
      current.repetitions = [current.position.repetitionKey: 1]
      current.warning = nil
      game = current
      publish()
      await sendReliably(.setup(name: await myName(), board: nil), type: "RMV", retries: 4)

    case .decline:
      guard current.phase == .inviting else { return true }
      cancelPending("INV")
      current.phase = .over
      current.ending = .opponentDeclined
      game = current
      publish()

    case let .setup(_, board):
      let key = "R:" + (board ?? "")
      guard current.phase == .waitingForSetup || pending["ACC"] != nil, key != current.setupKey else {
        await transmit(.ack("RMV"), to: fromKey)
        return true
      }
      cancelPending("ACC")
      current.phase = .playing
      current.setupKey = key
      if let board, let decoded = CoreChessBoard.decode(board) {
        current.position = decoded.position
        current.myColor = decoded.senderColor.opposite
      } else {
        current.position = .start
        current.myColor = .black
      }
      current.repetitions = [current.position.repetitionKey: 1]
      game = current
      publish()
      await transmit(.ack("RMV"), to: fromKey)

    case let .move(seq, move, _, _):
      await handleMove(seq: seq, move: move, current: current)

    case let .ack(what):
      if let seq = Int(what), seq > 0 {
        guard pending["MOV"]?.seq == seq else { return true }
        cancelPending("MOV")
        current.awaitingAck = false
        current.ackFailed = false
        game = current
        publish()
      } else {
        cancelPending(what)
      }

    case let .resign(reset):
      guard current.phase == .playing || current.phase == .over else { return true }
      cancelAllPending()
      if current.phase == .playing {
        current.phase = .over
        current.ending = reset ? .opponentResetBoard : .opponentResigned
        if !reset { record(.win, current) }
        game = current
        publish()
      }
      await transmit(.ack("RES"), to: fromKey)

    case let .draw(what):
      guard current.phase == .playing else {
        await transmit(.ack("DRW"), to: fromKey)
        return true
      }
      switch what {
      case "offer": current.drawOfferedByOpponent = true
      case "accept":
        current.drawOfferedByMe = false
        current.phase = .over
        current.ending = .drawAgreed
        record(.draw, current)
      case "decline": current.drawOfferedByMe = false
      default: break
      }
      game = current
      publish()
      await transmit(.ack("DRW"), to: fromKey)

    case .resyncRequest:
      if current.phase == .playing {
        lastSyncSent = Date()
        await transmit(.sync(board: CoreChessBoard.encode(current.position, senderColor: current.myColor), ply: current.ply), to: fromKey)
      } else {
        await transmit(.resign(reset: current.phase != .over), to: fromKey)
      }

    case let .sync(board, ply):
      await handleSync(board: board, ply: ply, current: current)
    }
    return true
  }

  private func handleInvite(from key: Data, name: String, fromOpponent: Bool) async {
    let me = await myName()
    if let current = game {
      if current.phase == .playing, !fromOpponent {
        await transmit(.decline(busy: true), to: key)
        return
      }
      // Our accept was lost and they invite again: answer again.
      if fromOpponent, current.phase == .waitingForSetup {
        await transmit(.accept(name: me), to: key)
        return
      }
      // Crossed invites: the alphabetically smaller node name stays the inviter.
      if fromOpponent, current.phase == .inviting {
        if !me.isEmpty, me.lowercased() < name.lowercased() { return }
        cancelPending("INV")
      }
    }
    game = ChessGame(phase: .invited, opponentKey: key, opponentName: name)
    publish()
  }

  private func handleMove(seq: Int, move wireMove: ChessPosition.Move, current: ChessGame) async {
    var current = current
    let key = current.opponentKey
    guard current.phase == .playing else {
      if seq > 0 { await transmit(.ack(String(seq)), to: key) }
      if current.phase == .over {
        if seq > 0, CoreChessMessage.isNewer(seq, than: current.seqIn) {
          await transmit(.resign(reset: false), to: key)
        }
      } else {
        await transmit(.resign(reset: true), to: key)
      }
      return
    }
    if seq > 0, !CoreChessMessage.isNewer(seq, than: current.seqIn) {
      await transmit(.ack(String(seq)), to: key)
      return
    }
    // Validate with our own rules; flags come from us, never from the wire.
    let opponentColor = current.myColor.opposite
    guard current.position.turn == opponentColor,
          let piece = current.position[wireMove.from], ChessPosition.color(of: piece) == opponentColor,
          let move = current.position.legalMove(from: wireMove.from, to: wireMove.to)
    else {
      current.warning = .resyncing
      game = current
      publish()
      if seq > 0 { await transmit(.ack(String(seq)), to: key) }
      await requestResync(to: key)
      return
    }
    if seq > 0 { current.seqIn = seq }
    // Their move means they had ours.
    if current.awaitingAck {
      cancelPending("MOV")
      current.awaitingAck = false
      current.ackFailed = false
    }
    current.position = current.position.applying(move)
    current.lastMove = move
    current.history.append(move.name)
    current.ply += 1
    current.warning = nil
    current.drawOfferedByMe = false
    recordAndCheckEnd(&current)
    game = current
    publish()
    await transmit(.ack(seq > 0 ? String(seq) : move.name), to: key)
  }

  private func handleSync(board: String, ply remotePly: Int?, current: ChessGame) async {
    var current = current
    guard current.phase == .playing, let decoded = CoreChessBoard.decode(board) else { return }
    if let remotePly, remotePly < current.ply {
      if Date().timeIntervalSince(lastSyncSent) > 5 {
        lastSyncSent = Date()
        await transmit(
          .sync(board: CoreChessBoard.encode(current.position, senderColor: current.myColor), ply: current.ply),
          to: current.opponentKey
        )
      }
      return
    }
    if remotePly == nil || remotePly == current.ply {
      guard Date().timeIntervalSince(lastResyncRequest) < 120 else { return }
    }
    current.position = decoded.position
    current.myColor = decoded.senderColor.opposite
    current.ply = remotePly ?? current.ply
    current.awaitingAck = false
    current.ackFailed = false
    current.warning = .resynced
    cancelPending("MOV")
    game = current
    publish()
  }

  private func requestResync(to key: Data) async {
    guard Date().timeIntervalSince(lastResyncRequest) > 8 else { return }
    lastResyncRequest = Date()
    await transmit(.resyncRequest, to: key)
  }

  // MARK: - Game end

  private func recordAndCheckEnd(_ current: inout ChessGame) {
    let key = current.position.repetitionKey
    current.repetitions[key, default: 0] += 1
    var tally: GameResult?
    switch current.position.status {
    case let .checkmate(winner):
      current.ending = .checkmate(youWon: winner == current.myColor)
      tally = winner == current.myColor ? .win : .loss
    case .stalemate:
      current.ending = .stalemate
      tally = .draw
    case .fiftyMoves:
      current.ending = .fiftyMoveRule
      tally = .draw
    case .insufficientMaterial:
      current.ending = .insufficientMaterial
      tally = .draw
    case .playing:
      if current.repetitions[key, default: 0] >= 3 {
        current.ending = .threefoldRepetition
        tally = .draw
      }
    }
    if let tally, current.phase != .over {
      current.phase = .over
      record(tally, current)
    }
  }

  /// Adds a finished game to the head-to-head record. Call once, where the game ends.
  private func record(_ result: GameResult, _ game: ChessGame) {
    guard let stats else { return }
    let opponent = game.opponentKey
    Task { await stats.record(result, game: GameStatsStore.chess, opponentKey: opponent) }
  }

  // MARK: - Sending

  private static func sameNode(_ a: Data, _ b: Data) -> Bool {
    let length = min(6, a.count, b.count)
    return length > 0 && a.prefix(length) == b.prefix(length)
  }

  private static func type(of text: String) -> String {
    String(text.dropFirst(CoreChessMessage.prefix.count).prefix { $0 != ":" })
  }

  private func logReceived(_ text: String, rx: GameRxInfo?) {
    game?.log.appendCapped(.received(Self.type(of: text), text, rx: rx))
    publish()
  }

  private func transmit(_ message: CoreChessMessage, to key: Data, attempt: Int = 1) async {
    var receipt: GameSendReceipt?
    var failure: Error?
    do {
      receipt = try await send(key, message.text)
    } catch {
      failure = error
      logger.warning("send \(message.text.prefix(10)) failed: \(error.localizedDescription)")
    }
    guard let current = game, Self.sameNode(current.opponentKey, key) else { return }
    let entry = GameLogEntry.sent(Self.type(of: message.text), message.text, attempt: attempt, receipt: receipt, error: failure)
    game?.log.appendCapped(entry)
    publish()
    if let radioAck = receipt?.radioAck {
      Task { [weak self] in
        let tripTime = await radioAck.value
        await self?.radioAcknowledged(entry.id, tripTime: tripTime)
      }
    }
  }

  private func radioAcknowledged(_ entry: UUID, tripTime: UInt32?) {
    guard game?.log.contains(where: { $0.id == entry }) == true else { return }
    game?.log.setRadioAck(entry, tripTime: tripTime)
    publish()
  }

  private func sendReliably(_ message: CoreChessMessage, type: String, retries: Int, seq: Int? = nil) async {
    guard let key = game?.opponentKey else { return }
    cancelPending(type)
    pending[type] = Pending(text: message.text, retriesLeft: retries, timeout: config.ackTimeout, task: nil, seq: seq)
    await transmit(message, to: key)
    scheduleRetry(type)
  }

  private func scheduleRetry(_ type: String) {
    guard let entry = pending[type] else { return }
    let timeout = entry.timeout
    pending[type]?.task = Task { [weak self] in
      try? await Task.sleep(for: timeout)
      guard !Task.isCancelled else { return }
      await self?.retry(type)
    }
  }

  private func retry(_ type: String) async {
    guard var entry = pending[type], let current = game else { return }
    guard entry.retriesLeft > 0 else {
      pending[type] = nil
      giveUp(type)
      return
    }
    entry.retriesLeft -= 1
    entry.attempt += 1
    // Duration has `*=` only for Int; the backoff is a Double.
    // swiftlint:disable:next shorthand_operator
    entry.timeout = entry.timeout * config.backoff
    pending[type] = entry
    if let message = CoreChessMessage(text: entry.text) {
      await transmit(message, to: current.opponentKey, attempt: entry.attempt)
    }
    scheduleRetry(type)
  }

  private func giveUp(_ type: String) {
    guard var current = game else { return }
    switch type {
    case "MOV":
      current.ackFailed = true
      current.warning = .moveNotAcknowledged
    case "INV":
      current.phase = .over
      current.ending = .inviteUnanswered
    case "ACC", "RMV":
      current.phase = .over
      current.ending = .setupFailed
    default:
      return
    }
    game = current
    publish()
  }

  private func cancelPending(_ type: String) {
    pending[type]?.task?.cancel()
    pending[type] = nil
  }

  private func cancelAllPending() {
    for type in Array(pending.keys) {
      cancelPending(type)
    }
  }
}
