import Foundation

/// Battleship on 10×10 with ships of 5, 4, 3, 3, 2 that may not touch, even diagonally.
///
/// Fairness: each side commits to its layout with `SHA-256(layout|salt)` before play and
/// reveals the layout at the end; the other side then checks every reported result.
///
/// Moves (wire text), alternating, player 0 first:
/// - `F:<cell>` the first shot, e.g. `F:B5` (column A–J, row 1–10)
/// - `R:<H|M|S>;F:<cell>` result of the opponent's last shot (hit, miss, sunk) plus our shot
/// - `R:X` the opponent's last shot sank our final ship: they win
public struct Battleship: Sendable, Equatable, Codable {
  public static let size = 10
  public static let fleet = [5, 4, 3, 3, 2]

  public struct Ship: Sendable, Equatable, Codable {
    public let origin: GridCell
    public let horizontal: Bool
    public let length: Int

    public init(origin: GridCell, horizontal: Bool, length: Int) {
      self.origin = origin
      self.horizontal = horizontal
      self.length = length
    }

    public var cells: [GridCell] {
      (0..<length).map { horizontal ? GridCell(row: origin.row, col: origin.col + $0) : GridCell(row: origin.row + $0, col: origin.col) }
    }
  }

  public enum Mark: String, Sendable, Codable {
    case miss = "M", hit = "H", sunk = "S"
  }

  public enum Verdict: Sendable, Equatable, Codable {
    case honest
    case cheated(CheatReason)
  }

  /// Why the opponent's revealed fleet was rejected.
  public enum CheatReason: Sendable, Equatable, Codable {
    case noCommitment
    /// The fleet does not match the commitment sent at the start.
    case fleetMismatch
    case invalidLayout
    /// A shot at `cell` was answered as a hit (or a miss) that the fleet contradicts.
    case falseAnswer(cell: String, reportedHit: Bool)
  }

  // Own side
  public private(set) var ships: [Ship]
  public let salt: String
  /// Opponent's shots at us and what we answered.
  public private(set) var incoming: [GridCell: Mark] = [:]

  /// Opponent side
  public var opponentCommit: String?
  /// Our shots and their answers.
  public private(set) var outgoing: [GridCell: Mark] = [:]
  /// Our shot waiting for its result.
  public private(set) var pendingShot: GridCell?

  public private(set) var turn = 0
  public private(set) var outcome: GameOutcome?
  public private(set) var verdict: Verdict?

  public init(ships: [Ship], salt: String = Battleship.randomSalt()) {
    self.ships = ships
    self.salt = salt
  }

  // MARK: Layout

  public static func randomSalt() -> String {
    GameSecret.randomSalt()
  }

  public static func randomFleet() -> [Ship] {
    while true {
      var ships: [Ship] = []
      var ok = true
      for length in fleet {
        var placed = false
        for _ in 0..<200 {
          let horizontal = Bool.random()
          let ship = Ship(
            origin: GridCell(
              row: Int.random(in: 0...(size - (horizontal ? 1 : length))),
              col: Int.random(in: 0...(size - (horizontal ? length : 1)))
            ),
            horizontal: horizontal, length: length
          )
          if fits(ship, among: ships) {
            ships.append(ship)
            placed = true
            break
          }
        }
        if !placed { ok = false; break }
      }
      if ok { return ships }
    }
  }

  /// Whether `ship` lies on the board without touching `ships`, even diagonally.
  public static func fits(_ ship: Ship, among ships: [Ship]) -> Bool {
    let taken = Set(ships.flatMap(\.cells))
    for cell in ship.cells {
      guard (0..<size).contains(cell.row), (0..<size).contains(cell.col) else { return false }
      for dr in -1...1 {
        for dc in -1...1 where taken.contains(GridCell(row: cell.row + dr, col: cell.col + dc)) {
          return false
        }
      }
    }
    return true
  }

  public static func valid(_ ships: [Ship]) -> Bool {
    guard ships.map(\.length) == fleet else { return false }
    var placed: [Ship] = []
    for ship in ships {
      guard fits(ship, among: placed) else { return false }
      placed.append(ship)
    }
    return true
  }

  /// `A1H,C3V,…` in fleet order.
  public static func encode(_ ships: [Ship]) -> String {
    ships.map { cellName($0.origin) + ($0.horizontal ? "H" : "V") }.joined(separator: ",")
  }

  public static func decode(_ text: String) -> [Ship]? {
    let parts = text.split(separator: ",")
    guard parts.count == fleet.count else { return nil }
    var ships: [Ship] = []
    for (part, length) in zip(parts, fleet) {
      guard let orientation = part.last, orientation == "H" || orientation == "V",
            let origin = parseCell(String(part.dropLast()))
      else { return nil }
      ships.append(Ship(origin: origin, horizontal: orientation == "H", length: length))
    }
    return valid(ships) ? ships : nil
  }

  public static func commitment(layout: String, salt: String) -> String {
    GameSecret.commitment(layout, salt: salt)
  }

  public var layoutText: String {
    Self.encode(ships)
  }

  public var commit: String {
    Self.commitment(layout: layoutText, salt: salt)
  }

  public static func cellName(_ cell: GridCell) -> String {
    String(UnicodeScalar(UInt8(65 + cell.col))) + "\(cell.row + 1)"
  }

  public static func parseCell(_ text: String) -> GridCell? {
    guard let letter = text.first?.asciiValue, let number = Int(text.dropFirst()) else { return nil }
    let cell = GridCell(row: number - 1, col: Int(letter) - 65)
    return (0..<size).contains(cell.row) && (0..<size).contains(cell.col) ? cell : nil
  }

  // MARK: Play

  /// Our answer to a shot at `cell`.
  func answer(to cell: GridCell) -> Mark {
    guard let ship = ships.first(where: { $0.cells.contains(cell) }) else { return .miss }
    let hits = ship.cells.filter { $0 == cell || incoming[$0] == .hit || incoming[$0] == .sunk }
    return hits.count == ship.length ? .sunk : .hit
  }

  private var allSunk: Bool {
    ships.allSatisfy { ship in ship.cells.allSatisfy { incoming[$0] == .hit || incoming[$0] == .sunk } }
  }

  /// Builds our move text for a shot at `target` (answering the opponent's pending shot, if any).
  /// Returns nil if the shot is not allowed.
  public func moveText(shootingAt target: GridCell, answering opponentShot: GridCell?) -> String? {
    guard outcome == nil, outgoing[target] == nil, pendingShot == nil else { return nil }
    let shot = "F:" + Self.cellName(target)
    guard let opponentShot else { return shot }
    return "R:\(answer(to: opponentShot).rawValue);" + shot
  }

  /// Text that ends the game when the opponent's shot sinks our last ship.
  public func losingAnswer(to opponentShot: GridCell) -> String? {
    var copy = self
    copy.incoming[opponentShot] = answer(to: opponentShot)
    return copy.allSunk ? "R:X" : nil
  }

  /// Applies a move by `player` (`me` tells which side is ours).
  public mutating func apply(_ move: String, by player: Int, me: Int) -> Bool {
    guard outcome == nil, player == turn else { return false }
    var shot: GridCell?
    var result: String?
    for part in move.split(separator: ";") {
      if part.hasPrefix("F:") { shot = Self.parseCell(String(part.dropFirst(2))) }
      if part.hasPrefix("R:") { result = String(part.dropFirst(2)) }
    }
    if player == me {
      // Our own move: record what we answered and the shot we fired.
      if let result, let opponentShot = lastOpponentShot {
        incoming[opponentShot] = result == "X" ? .sunk : Mark(rawValue: result) ?? .miss
        lastOpponentShot = nil
      }
      if result == "X" {
        outcome = .win(1 - me)
        return true
      }
      guard let shot else { return false }
      pendingShot = shot
    } else {
      // Opponent: their answer resolves our pending shot; their shot waits for our answer.
      if let result, let pending = pendingShot {
        outgoing[pending] = result == "X" ? .sunk : Mark(rawValue: result) ?? .miss
        pendingShot = nil
      }
      if result == "X" {
        outcome = .win(me)
        return true
      }
      guard let shot else { return false }
      lastOpponentShot = shot
    }
    turn = 1 - turn
    return true
  }

  /// The opponent's shot we have yet to answer.
  public private(set) var lastOpponentShot: GridCell?

  /// Checks the opponent's revealed layout against their commitment and every answer they gave.
  public mutating func verifyReveal(layout: String, salt: String) {
    guard let commit = opponentCommit else { verdict = .cheated(.noCommitment); return }
    guard Self.commitment(layout: layout, salt: salt) == commit else {
      verdict = .cheated(.fleetMismatch)
      return
    }
    guard let ships = Self.decode(layout) else { verdict = .cheated(.invalidLayout); return }
    let shipCells = Set(ships.flatMap(\.cells))
    for (cell, mark) in outgoing {
      let wasShip = shipCells.contains(cell)
      if (mark == .miss) == wasShip {
        verdict = .cheated(.falseAnswer(cell: Self.cellName(cell), reportedHit: mark != .miss))
        return
      }
    }
    verdict = .honest
  }

  /// Counts for the UI.
  public var shipsLeft: Int {
    ships.filter { ship in !ship.cells.allSatisfy { incoming[$0] == .hit || incoming[$0] == .sunk } }.count
  }

  public var opponentShipsSunk: Int {
    outgoing.values.filter { $0 == .sunk }.count
  }
}
