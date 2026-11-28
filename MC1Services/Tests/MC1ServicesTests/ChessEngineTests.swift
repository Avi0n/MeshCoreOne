import Foundation
@testable import MC1Services
import Testing

@Suite("ChessEngine")
struct ChessEngineTests {
  private func perft(_ position: ChessPosition, depth: Int) -> Int {
    guard depth > 0 else { return 1 }
    let moves = position.legalMoves()
    if depth == 1 { return moves.count }
    return moves.reduce(0) { $0 + perft(position.applying($1), depth: depth - 1) }
  }

  @Test
  func `perft from the start position`() {
    #expect(perft(.start, depth: 1) == 20)
    #expect(perft(.start, depth: 2) == 400)
    #expect(perft(.start, depth: 3) == 8902)
  }

  @Test
  func `perft 'Kiwipete': castling, en passant, pins`() throws {
    let position = try #require(ChessPosition(fen: "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -"))
    #expect(perft(position, depth: 1) == 48)
    #expect(perft(position, depth: 2) == 2039)
  }

  @Test
  func `perft position 3: en passant discovered checks`() throws {
    let position = try #require(ChessPosition(fen: "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - -"))
    #expect(perft(position, depth: 1) == 14)
    #expect(perft(position, depth: 2) == 191)
    #expect(perft(position, depth: 3) == 2812)
  }

  @Test
  func `castling carries the CoreChess tag and moves the rook`() throws {
    let position = try #require(ChessPosition(fen: "r3k2r/8/8/8/8/8/8/R3K2R w KQkq -"))
    let short = try #require(position.legalMove(from: .init(row: 7, col: 4), to: .init(row: 7, col: 6)))
    #expect(short.castle == .wK)
    let after = position.applying(short)
    #expect(after.board[7][5] == "R")
    #expect(after.board[7][7] == nil)
    #expect(!after.castling.wK && !after.castling.wQ)
  }

  @Test
  func `promotion is always to a queen`() throws {
    let position = try #require(ChessPosition(fen: "8/P6k/8/8/8/8/8/K7 w - -"))
    let move = try #require(position.legalMove(from: .init(row: 1, col: 0), to: .init(row: 0, col: 0)))
    #expect(position.applying(move).board[0][0] == "Q")
  }

  @Test
  func `fool's mate is checkmate`() throws {
    var position = ChessPosition.start
    for (from, to) in [((6, 5), (5, 5)), ((1, 4), (3, 4)), ((6, 6), (4, 6)), ((0, 3), (4, 7))] {
      let move = try #require(position.legalMove(from: .init(row: from.0, col: from.1), to: .init(row: to.0, col: to.1)))
      position = position.applying(move)
    }
    #expect(position.status == .checkmate(winner: .black))
  }

  @Test
  func `CoreChess board hash matches values computed by its JavaScript in Node`() throws {
    var position = ChessPosition.start
    position = try position.applying(#require(position.legalMove(from: .init(row: 6, col: 4), to: .init(row: 4, col: 4))))
    #expect(position.coreChessHash(turnColor: .black) == "42f0")
    #expect(ChessPosition.coreChessHash("8/8/8/8/8/8/8/K6k/|white") == "4874")
    #expect(ChessPosition.coreChessHash("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R/|white") == "dbb4")
  }
}
