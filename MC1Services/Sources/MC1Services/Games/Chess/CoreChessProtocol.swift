import Foundation

/// CoreChess wire format (https://corechess.netlify.app), carried as plain-text DMs.
///
/// `CC:<TYPE>[:<payload>]`
/// - `INV:<name>` invite · `ACC:<name>` accept · `DEC[:busy]` decline
/// - `RMV:<name>` new game, inviter plays white · `RMV:<name>;<board35>` resume a saved board
/// - `MOV:<seq>:<fr><fc><tr><tc><piece>[wK|wQ|bK|bQ][E]#<hash>` move (row 0 = rank 8)
/// - `ACK:<seq>` for moves, `ACK:<TYPE>` for `RES`/`RMV`/`DRW`
/// - `RES` resign · `RES:reset` abandon · `DRW:offer|accept|decline`
/// - `RSY` asks for the board · `SYN:<board35>|<ply>` sends it
public enum CoreChessMessage: Sendable, Equatable {
  case invite(name: String)
  case accept(name: String)
  case decline(busy: Bool)
  case setup(name: String, board: String?)
  case move(seq: Int, move: ChessPosition.Move, piece: Character, hash: String?)
  case ack(String)
  case resign(reset: Bool)
  case draw(String)
  case resyncRequest
  case sync(board: String, ply: Int?)

  public static let prefix = "CC:"

  public var text: String {
    switch self {
    case let .invite(name): "CC:INV:\(name)"
    case let .accept(name): "CC:ACC:\(name)"
    case let .decline(busy): busy ? "CC:DEC:busy" : "CC:DEC"
    case let .setup(name, board): "CC:RMV:\(name)" + (board.map { ";" + $0 } ?? "")
    case let .move(seq, move, piece, hash):
      "CC:MOV:\(seq):\(move.from.row)\(move.from.col)\(move.to.row)\(move.to.col)\(piece)"
        + (move.castle?.rawValue ?? "") + (move.enPassant ? "E" : "") + (hash.map { "#" + $0 } ?? "")
    case let .ack(what): "CC:ACK:\(what)"
    case let .resign(reset): reset ? "CC:RES:reset" : "CC:RES"
    case let .draw(what): "CC:DRW:\(what)"
    case .resyncRequest: "CC:RSY"
    case let .sync(board, ply): "CC:SYN:\(board)" + (ply.map { "|\($0)" } ?? "")
    }
  }

  public init?(text: String) {
    guard text.hasPrefix(Self.prefix) else { return nil }
    let rest = text.dropFirst(Self.prefix.count)
    let type: Substring
    let payload: String
    if let colon = rest.firstIndex(of: ":") {
      type = rest[..<colon]
      payload = String(rest[rest.index(after: colon)...])
    } else {
      type = rest
      payload = ""
    }
    switch type {
    case "INV": self = .invite(name: payload)
    case "ACC": self = .accept(name: payload)
    case "DEC": self = .decline(busy: payload == "busy")
    case "RMV":
      if let semicolon = payload.firstIndex(of: ";") {
        self = .setup(name: String(payload[..<semicolon]), board: String(payload[payload.index(after: semicolon)...]))
      } else {
        self = .setup(name: payload, board: nil)
      }
    case "MOV":
      guard let parsed = Self.parseMove(payload) else { return nil }
      self = parsed
    case "ACK": self = .ack(payload)
    case "RES": self = .resign(reset: payload == "reset")
    case "DRW": self = .draw(payload)
    case "RSY": self = .resyncRequest
    case "SYN":
      if let bar = payload.lastIndex(of: "|"), let ply = Int(payload[payload.index(after: bar)...]) {
        self = .sync(board: String(payload[..<bar]), ply: ply)
      } else {
        self = .sync(board: payload, ply: nil)
      }
    default: return nil
    }
  }

  private static func parseMove(_ payload: String) -> CoreChessMessage? {
    var seq = 0
    var body = Substring(payload)
    if let colon = body.firstIndex(of: ":") {
      seq = Int(body[..<colon]) ?? 0
      body = body[body.index(after: colon)...]
    }
    var hash: String?
    if let hashIndex = body.firstIndex(of: "#") {
      hash = String(body[body.index(after: hashIndex)...])
      body = body[..<hashIndex]
    }
    let chars = Array(body)
    guard chars.count >= 5,
          let fr = chars[0].wholeNumberValue, let fc = chars[1].wholeNumberValue,
          let tr = chars[2].wholeNumberValue, let tc = chars[3].wholeNumberValue
    else { return nil }
    let piece = chars[4]
    var rest = String(chars[5...])
    var castle: ChessPosition.Castle?
    for tag in [ChessPosition.Castle.wK, .wQ, .bK, .bQ] where rest.hasPrefix(tag.rawValue) {
      castle = tag
      rest.removeFirst(tag.rawValue.count)
      break
    }
    let move = ChessPosition.Move(
      from: .init(row: fr, col: fc), to: .init(row: tr, col: tc), castle: castle, enPassant: rest.hasPrefix("E")
    )
    guard move.from.isValid, move.to.isValid else { return nil }
    return .move(seq: seq, move: move, piece: piece, hash: hash)
  }

  /// CoreChess `_seqIsNewer`: move sequence numbers run 1…999 and wrap.
  public static func isNewer(_ seq: Int, than last: Int) -> Bool {
    guard last > 0 else { return true }
    let delta = ((seq - last) % 999 + 999) % 999
    return delta > 0 && delta < 500
  }
}

/// CoreChess 35-byte board snapshot, base64: 64 nibbles (piece codes 1–12, even square in the low
/// nibble), flags (bit0 sender plays white, bit1 white to move, bits 2–5 castling KQkq),
/// en passant square (`row*8+col`, 255 none), half-move clock.
public enum CoreChessBoard {
  private static let codes: [Character: UInt8] = [
    "P": 1, "N": 2, "B": 3, "R": 4, "Q": 5, "K": 6, "p": 7, "n": 8, "b": 9, "r": 10, "q": 11, "k": 12
  ]
  private static let pieces: [Character?] = [nil, "P", "N", "B", "R", "Q", "K", "p", "n", "b", "r", "q", "k"]

  public static func encode(_ position: ChessPosition, senderColor: ChessPosition.Color) -> String {
    var bytes = [UInt8](repeating: 0, count: 35)
    for index in 0..<64 {
      let value = position.board[index >> 3][index & 7].flatMap { codes[$0] } ?? 0
      bytes[index >> 1] |= (index & 1) == 1 ? value << 4 : value
    }
    let c = position.castling
    bytes[32] = (senderColor == .white ? 1 : 0) | (position.turn == .white ? 2 : 0)
      | (c.wK ? 4 : 0) | (c.wQ ? 8 : 0) | (c.bK ? 16 : 0) | (c.bQ ? 32 : 0)
    bytes[33] = position.enPassant.map { UInt8($0.row * 8 + $0.col) } ?? 255
    bytes[34] = UInt8(min(max(position.halfMoves, 0), 255))
    return Data(bytes).base64EncodedString()
  }

  /// The position and the colour the *sender* plays.
  public static func decode(_ text: String) -> (position: ChessPosition, senderColor: ChessPosition.Color)? {
    guard let data = Data(base64Encoded: text), data.count >= 34 else { return nil }
    let bytes = [UInt8](data)
    var board = [[Character?]](repeating: [Character?](repeating: nil, count: 8), count: 8)
    for index in 0..<64 {
      let value = (index & 1) == 1 ? bytes[index >> 1] >> 4 : bytes[index >> 1] & 0x0F
      if value > 0, value <= 12 { board[index >> 3][index & 7] = pieces[Int(value)] }
    }
    let flags = bytes[32]
    var castling = ChessPosition.CastlingRights()
    castling.wK = flags & 4 != 0
    castling.wQ = flags & 8 != 0
    castling.bK = flags & 16 != 0
    castling.bQ = flags & 32 != 0
    let ep: ChessPosition.Square? = bytes[33] == 255 ? nil : .init(row: Int(bytes[33]) / 8, col: Int(bytes[33]) & 7)
    let position = ChessPosition(
      board: board, turn: flags & 2 != 0 ? .white : .black, castling: castling, enPassant: ep,
      halfMoves: bytes.count >= 35 ? Int(bytes[34]) : 0
    )
    guard position.kingSquare(.white) != nil, position.kingSquare(.black) != nil else { return nil }
    return (position, flags & 1 != 0 ? .white : .black)
  }
}
