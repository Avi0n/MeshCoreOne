import Foundation
@testable import MC1Services
import Testing

@Suite("Game rules")
struct GameRulesTests {
  @Test
  func `battleship: layout, commitment, a game to the end, honest reveal`() throws {
    let fleetA = Battleship.randomFleet()
    let fleetB = Battleship.randomFleet()
    #expect(Battleship.valid(fleetA))
    #expect(Battleship.decode(Battleship.encode(fleetA)) == fleetA)

    var a = Battleship(ships: fleetA)
    var b = Battleship(ships: fleetB)
    a.opponentCommit = b.commit
    b.opponentCommit = a.commit

    // A shoots every cell of B's fleet in order; B always shoots open water it has not tried.
    let targets = fleetB.flatMap(\.cells)
    var bTargets = (0..<100).map { GridCell(row: $0 / 10, col: $0 % 10) }.makeIterator()
    var moves = 0
    func send(_ text: String, from player: Int) {
      do { let ok = a.apply(text, by: player, me: 0); #expect(ok) }
      do { let ok = b.apply(text, by: player, me: 1); #expect(ok) }
    }
    try send(#require(a.moveText(shootingAt: targets[0], answering: nil)), from: 0)
    var index = 1
    while a.outcome == nil {
      // B answers A's shot.
      let shotAtB = try #require(b.lastOpponentShot)
      if let final = b.losingAnswer(to: shotAtB) {
        send(final, from: 1)
        break
      }
      // `#require` cannot call a mutating method, so step the iterator first.
      var next = bTargets.next()
      var bShot = try #require(next)
      while b.outgoing[bShot] != nil {
        next = bTargets.next()
        bShot = try #require(next)
      }
      try send(#require(b.moveText(shootingAt: bShot, answering: shotAtB)), from: 1)
      let shotAtA = try #require(a.lastOpponentShot)
      #expect(a.losingAnswer(to: shotAtA) == nil)
      try send(#require(a.moveText(shootingAt: targets[index], answering: shotAtA)), from: 0)
      index += 1
      moves += 1
      #expect(moves < 40)
    }
    #expect(a.outcome == .win(0))
    #expect(b.outcome == .win(0))
    #expect(a.opponentShipsSunk == 5)

    a.verifyReveal(layout: b.layoutText, salt: b.salt)
    #expect(a.verdict == .honest)
    b.verifyReveal(layout: a.layoutText, salt: a.salt)
    #expect(b.verdict == .honest)
  }

  @Test
  func `battleship: a lie about a hit is caught at the reveal`() {
    let fleetB = Battleship.randomFleet()
    var a = Battleship(ships: Battleship.randomFleet())
    let b = Battleship(ships: fleetB)
    a.opponentCommit = b.commit
    let shipCell = fleetB[0].cells[0]
    do { let ok = a.apply("F:" + Battleship.cellName(shipCell), by: 0, me: 0); #expect(ok) }
    // B claims a miss on a ship cell.
    do { let ok = a.apply("R:M;F:A1", by: 1, me: 0); #expect(ok) }
    a.verifyReveal(layout: b.layoutText, salt: b.salt)
    #expect(a.verdict == .cheated(.falseAnswer(cell: Battleship.cellName(shipCell), reportedHit: false)))
  }
}
