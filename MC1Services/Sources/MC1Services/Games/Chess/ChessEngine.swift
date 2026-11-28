import Foundation

/// Chess rules, using CoreChess conventions so positions and moves match it exactly:
/// row 0 is rank 8, column 0 is file a, white pieces are uppercase (`PNBRQK`),
/// and a pawn reaching the last rank always becomes a queen.
public struct ChessPosition: Sendable, Equatable, Codable {
  public enum Color: String, Sendable, Codable {
    case white, black

    public var opposite: Color { self == .white ? .black : .white }
  }

  public struct Square: Sendable, Hashable, Codable {
    public let row: Int
    public let col: Int

    public init(row: Int, col: Int) {
      self.row = row
      self.col = col
    }

    public var isValid: Bool { (0..<8).contains(row) && (0..<8).contains(col) }
    /// Algebraic name, e.g. `e4`.
    public var name: String { String(UnicodeScalar(UInt8(97 + col))) + "\(8 - row)" }
  }

  /// CoreChess castle tags carried on the king's move.
  public enum Castle: String, Sendable, Codable {
    case wK, wQ, bK, bQ
  }

  public struct Move: Sendable, Hashable, Codable {
    public let from: Square
    public let to: Square
    public var castle: Castle?
    public var enPassant: Bool

    public init(from: Square, to: Square, castle: Castle? = nil, enPassant: Bool = false) {
      self.from = from
      self.to = to
      self.castle = castle
      self.enPassant = enPassant
    }

    /// Coordinate notation, e.g. `e2-e4`.
    public var name: String { "\(from.name)-\(to.name)" }
  }

  public struct CastlingRights: Sendable, Equatable, Codable {
    public var wK = true, wQ = true, bK = true, bQ = true
    public init() {}
  }

  /// `board[row][col]`, nil = empty.
  public var board: [[Character?]]
  public var turn: Color
  public var castling: CastlingRights
  public var enPassant: Square?
  public var halfMoves: Int

  public init(board: [[Character?]], turn: Color, castling: CastlingRights, enPassant: Square?, halfMoves: Int = 0) {
    self.board = board
    self.turn = turn
    self.castling = castling
    self.enPassant = enPassant
    self.halfMoves = halfMoves
  }

  private enum CodingKeys: String, CodingKey {
    case board, turn, castling, enPassant, halfMoves
  }

  public init(from decoder: Decoder) throws {
    let container = try decoder.container(keyedBy: CodingKeys.self)
    let rows = try container.decode([String].self, forKey: .board)
    guard rows.count == 8, rows.allSatisfy({ $0.count == 8 }) else {
      throw DecodingError.dataCorruptedError(forKey: .board, in: container, debugDescription: "8x8 board expected")
    }
    board = rows.map { row in row.map { $0 == "." ? nil : $0 } }
    turn = try container.decode(Color.self, forKey: .turn)
    castling = try container.decode(CastlingRights.self, forKey: .castling)
    enPassant = try container.decodeIfPresent(Square.self, forKey: .enPassant)
    halfMoves = try container.decode(Int.self, forKey: .halfMoves)
  }

  public func encode(to encoder: Encoder) throws {
    var container = encoder.container(keyedBy: CodingKeys.self)
    try container.encode(board.map { String($0.map { $0 ?? "." }) }, forKey: .board)
    try container.encode(turn, forKey: .turn)
    try container.encode(castling, forKey: .castling)
    try container.encodeIfPresent(enPassant, forKey: .enPassant)
    try container.encode(halfMoves, forKey: .halfMoves)
  }

  public static var start: ChessPosition {
    let back: [Character] = ["r", "n", "b", "q", "k", "b", "n", "r"]
    var board = [[Character?]](repeating: [Character?](repeating: nil, count: 8), count: 8)
    board[0] = back
    board[1] = [Character?](repeating: "p", count: 8)
    board[6] = [Character?](repeating: "P", count: 8)
    board[7] = back.map { Character($0.uppercased()) }
    return ChessPosition(board: board, turn: .white, castling: CastlingRights(), enPassant: nil)
  }

  /// Parses the board, side to move, castling and en passant fields of a FEN.
  public init?(fen: String) {
    let fields = fen.split(separator: " ").map(String.init)
    guard fields.count >= 2 else { return nil }
    let ranks = fields[0].split(separator: "/")
    guard ranks.count == 8 else { return nil }
    var board: [[Character?]] = []
    for rank in ranks {
      var row: [Character?] = []
      for char in rank {
        if let empty = char.wholeNumberValue {
          row.append(contentsOf: [Character?](repeating: nil, count: empty))
        } else {
          row.append(char)
        }
      }
      guard row.count == 8 else { return nil }
      board.append(row)
    }
    var rights = CastlingRights()
    let castleField = fields.count > 2 ? fields[2] : "-"
    rights.wK = castleField.contains("K")
    rights.wQ = castleField.contains("Q")
    rights.bK = castleField.contains("k")
    rights.bQ = castleField.contains("q")
    var ep: Square?
    if fields.count > 3, fields[3] != "-", fields[3].count == 2,
       let file = fields[3].first?.asciiValue, let rank = fields[3].last?.wholeNumberValue {
      ep = Square(row: 8 - rank, col: Int(file) - 97)
    }
    self.init(board: board, turn: fields[1] == "b" ? .black : .white, castling: rights, enPassant: ep)
  }

  public subscript(_ square: Square) -> Character? {
    get { board[square.row][square.col] }
    set { board[square.row][square.col] = newValue }
  }

  public static func color(of piece: Character) -> Color { piece.isUppercase ? .white : .black }

  // MARK: Move generation

  /// Moves that follow piece movement rules, ignoring whether the own king is left in check.
  func pseudoMoves(from square: Square) -> [Move] {
    guard let piece = self[square], Self.color(of: piece) == turn else { return [] }
    let own = turn
    var moves: [Move] = []

    func add(_ to: Square, castle: Castle? = nil, ep: Bool = false) {
      moves.append(Move(from: square, to: to, castle: castle, enPassant: ep))
    }
    func isEnemy(_ s: Square) -> Bool { self[s].map { Self.color(of: $0) != own } ?? false }
    func slide(_ directions: [(Int, Int)], repeatSteps: Bool) {
      for (dr, dc) in directions {
        var target = Square(row: square.row + dr, col: square.col + dc)
        while target.isValid {
          if self[target] == nil {
            add(target)
          } else {
            if isEnemy(target) { add(target) }
            break
          }
          if !repeatSteps { break }
          target = Square(row: target.row + dr, col: target.col + dc)
        }
      }
    }

    switch piece.uppercased() {
    case "P":
      let dir = own == .white ? -1 : 1
      let startRow = own == .white ? 6 : 1
      let one = Square(row: square.row + dir, col: square.col)
      if one.isValid, self[one] == nil {
        add(one)
        let two = Square(row: square.row + 2 * dir, col: square.col)
        if square.row == startRow, self[two] == nil { add(two) }
      }
      for dc in [-1, 1] {
        let diag = Square(row: square.row + dir, col: square.col + dc)
        guard diag.isValid else { continue }
        if isEnemy(diag) {
          add(diag)
        } else if diag == enPassant, self[diag] == nil {
          add(diag, ep: true)
        }
      }
    case "N":
      for (dr, dc) in [(-2, -1), (-2, 1), (-1, -2), (-1, 2), (1, -2), (1, 2), (2, -1), (2, 1)] {
        let target = Square(row: square.row + dr, col: square.col + dc)
        if target.isValid, self[target] == nil || isEnemy(target) { add(target) }
      }
    case "B":
      slide([(-1, -1), (-1, 1), (1, -1), (1, 1)], repeatSteps: true)
    case "R":
      slide([(-1, 0), (1, 0), (0, -1), (0, 1)], repeatSteps: true)
    case "Q":
      slide([(-1, -1), (-1, 1), (1, -1), (1, 1), (-1, 0), (1, 0), (0, -1), (0, 1)], repeatSteps: true)
    case "K":
      slide([(-1, -1), (-1, 1), (1, -1), (1, 1), (-1, 0), (1, 0), (0, -1), (0, 1)], repeatSteps: false)
      let row = own == .white ? 7 : 0
      let kingSide: (allowed: Bool, tag: Castle) = own == .white ? (castling.wK, .wK) : (castling.bK, .bK)
      let queenSide: (allowed: Bool, tag: Castle) = own == .white ? (castling.wQ, .wQ) : (castling.bQ, .bQ)
      let rook: Character = own == .white ? "R" : "r"
      if square == Square(row: row, col: 4), !isAttacked(square, by: own.opposite) {
        if kingSide.allowed, board[row][7] == rook, board[row][5] == nil, board[row][6] == nil,
           !isAttacked(Square(row: row, col: 5), by: own.opposite),
           !isAttacked(Square(row: row, col: 6), by: own.opposite) {
          add(Square(row: row, col: 6), castle: kingSide.tag)
        }
        if queenSide.allowed, board[row][0] == rook, board[row][1] == nil, board[row][2] == nil, board[row][3] == nil,
           !isAttacked(Square(row: row, col: 3), by: own.opposite),
           !isAttacked(Square(row: row, col: 2), by: own.opposite) {
          add(Square(row: row, col: 2), castle: queenSide.tag)
        }
      }
    default:
      break
    }
    return moves
  }

  /// Whether `square` is attacked by any piece of `color`.
  public func isAttacked(_ square: Square, by color: Color) -> Bool {
    let pawn: Character = color == .white ? "P" : "p"
    let pawnRow = square.row + (color == .white ? 1 : -1)
    for dc in [-1, 1] {
      let s = Square(row: pawnRow, col: square.col + dc)
      if s.isValid, self[s] == pawn { return true }
    }
    let knight: Character = color == .white ? "N" : "n"
    for (dr, dc) in [(-2, -1), (-2, 1), (-1, -2), (-1, 2), (1, -2), (1, 2), (2, -1), (2, 1)] {
      let s = Square(row: square.row + dr, col: square.col + dc)
      if s.isValid, self[s] == knight { return true }
    }
    let king: Character = color == .white ? "K" : "k"
    for dr in -1...1 {
      for dc in -1...1 where dr != 0 || dc != 0 {
        let s = Square(row: square.row + dr, col: square.col + dc)
        if s.isValid, self[s] == king { return true }
      }
    }
    let rookLike: Set<Character> = color == .white ? ["R", "Q"] : ["r", "q"]
    let bishopLike: Set<Character> = color == .white ? ["B", "Q"] : ["b", "q"]
    for (directions, attackers) in [
      ([(-1, 0), (1, 0), (0, -1), (0, 1)], rookLike),
      ([(-1, -1), (-1, 1), (1, -1), (1, 1)], bishopLike)
    ] {
      for (dr, dc) in directions {
        var s = Square(row: square.row + dr, col: square.col + dc)
        while s.isValid {
          if let piece = self[s] {
            if attackers.contains(piece) { return true }
            break
          }
          s = Square(row: s.row + dr, col: s.col + dc)
        }
      }
    }
    return false
  }

  public func kingSquare(_ color: Color) -> Square? {
    let king: Character = color == .white ? "K" : "k"
    for row in 0..<8 {
      for col in 0..<8 where board[row][col] == king {
        return Square(row: row, col: col)
      }
    }
    return nil
  }

  public func isInCheck(_ color: Color) -> Bool {
    guard let king = kingSquare(color) else { return false }
    return isAttacked(king, by: color.opposite)
  }

  public func legalMoves(from square: Square) -> [Move] {
    pseudoMoves(from: square).filter { !applying($0).isInCheck(turn) }
  }

  public func legalMoves() -> [Move] {
    var all: [Move] = []
    for row in 0..<8 {
      for col in 0..<8 {
        all.append(contentsOf: legalMoves(from: Square(row: row, col: col)))
      }
    }
    return all
  }

  // MARK: Applying

  /// The position after `move` (assumed legal), with the turn passed to the other side.
  public func applying(_ move: Move) -> ChessPosition {
    var next = self
    guard let piece = self[move.from] else { return next }
    let captured = self[move.to]
    next[move.to] = piece
    next[move.from] = nil
    var capture = captured != nil
    if piece.uppercased() == "P", move.from.col != move.to.col, captured == nil {
      next.board[move.from.row][move.to.col] = nil
      capture = true
    }
    if piece.uppercased() == "P", move.to.row == 0 || move.to.row == 7 {
      next[move.to] = piece.isUppercase ? "Q" : "q"
    }
    next.enPassant = nil
    if piece.uppercased() == "P", abs(move.to.row - move.from.row) == 2 {
      next.enPassant = Square(row: (move.from.row + move.to.row) / 2, col: move.from.col)
    }
    switch move.castle {
    case .wK?: next.board[7][5] = next.board[7][7]; next.board[7][7] = nil
    case .wQ?: next.board[7][3] = next.board[7][0]; next.board[7][0] = nil
    case .bK?: next.board[0][5] = next.board[0][7]; next.board[0][7] = nil
    case .bQ?: next.board[0][3] = next.board[0][0]; next.board[0][0] = nil
    case nil: break
    }
    if piece == "K" { next.castling.wK = false; next.castling.wQ = false }
    if piece == "k" { next.castling.bK = false; next.castling.bQ = false }
    for square in [move.from, move.to] {
      switch (square.row, square.col) {
      case (7, 7): next.castling.wK = false
      case (7, 0): next.castling.wQ = false
      case (0, 7): next.castling.bK = false
      case (0, 0): next.castling.bQ = false
      default: break
      }
    }
    next.halfMoves = (piece.uppercased() == "P" || capture) ? 0 : halfMoves + 1
    next.turn = turn.opposite
    return next
  }

  /// Fills in the castle / en passant flags from our own rules (never trust the wire).
  public func legalMove(from: Square, to: Square) -> Move? {
    legalMoves(from: from).first { $0.to == to }
  }

  // MARK: Game end

  public enum Status: Sendable, Equatable {
    case playing
    case checkmate(winner: Color)
    case stalemate
    case fiftyMoves
    case insufficientMaterial
  }

  public var status: Status {
    if legalMoves().isEmpty {
      return isInCheck(turn) ? .checkmate(winner: turn.opposite) : .stalemate
    }
    if halfMoves >= 100 { return .fiftyMoves }
    if hasInsufficientMaterial { return .insufficientMaterial }
    return .playing
  }

  var hasInsufficientMaterial: Bool {
    var minor: [(Character, Int)] = []
    for row in 0..<8 {
      for col in 0..<8 {
        guard let piece = board[row][col] else { continue }
        switch piece.uppercased() {
        case "K": continue
        case "N", "B": minor.append((piece, (row + col) % 2))
        default: return false
        }
      }
    }
    if minor.count <= 1 { return true }
    // Only bishops, all on one colour of square.
    return minor.allSatisfy { $0.0.uppercased() == "B" } && Set(minor.map(\.1)).count == 1
  }

  // MARK: CoreChess identity

  /// Board part of a FEN, each rank followed by `/` (CoreChess hashes it this way).
  var placementWithTrailingSlashes: String {
    board.map { row in
      var text = "", empty = 0
      for square in row {
        if let piece = square {
          if empty > 0 { text += "\(empty)"; empty = 0 }
          text.append(piece)
        } else {
          empty += 1
        }
      }
      if empty > 0 { text += "\(empty)" }
      return text + "/"
    }.joined()
  }

  /// CoreChess board hash over `placement|turnColor`, last 4 hex digits.
  public func coreChessHash(turnColor: Color) -> String {
    Self.coreChessHash(placementWithTrailingSlashes + "|" + turnColor.rawValue)
  }

  /// FNV-1a-like hash exactly as CoreChess's JavaScript computes it: `^` yields a signed
  /// 32-bit value and the multiply runs in doubles (losing precision) before `>>> 0`.
  static func coreChessHash(_ text: String) -> String {
    var hash: UInt32 = 0x811C_9DC5
    for unit in text.utf16 {
      let mixed = Int32(bitPattern: hash ^ UInt32(unit))
      var product = (Double(mixed) * 16_777_619.0).truncatingRemainder(dividingBy: 4_294_967_296.0)
      if product < 0 { product += 4_294_967_296.0 }
      hash = UInt32(product)
    }
    let hex = String(hash, radix: 16)
    return String((String(repeating: "0", count: max(0, 8 - hex.count)) + hex).suffix(4))
  }

  /// Key for threefold repetition.
  public var repetitionKey: String {
    let c = castling
    return placementWithTrailingSlashes + turn.rawValue
      + (c.wK ? "K" : "") + (c.wQ ? "Q" : "") + (c.bK ? "k" : "") + (c.bQ ? "q" : "")
      + (enPassant.map { "\($0.row)\($0.col)" } ?? "-")
  }
}
