/// `begin` opens a closed gate and bumps once. `end` closes an open gate and bumps once.
/// A second call does neither, so a late reply cannot match the next visit.
@MainActor
struct LoadVisitGate {
  private var generation: UInt = 0
  private var acceptsLoads = true

  struct Token: Equatable {
    fileprivate let generation: UInt
    fileprivate let acceptsLoads: Bool
  }

  var isOpen: Bool {
    acceptsLoads
  }

  func capture() -> Token {
    Token(generation: generation, acceptsLoads: acceptsLoads)
  }

  func allows(_ token: Token) -> Bool {
    token.acceptsLoads && token.generation == generation
  }

  mutating func begin() {
    guard !acceptsLoads else { return }
    generation &+= 1
    acceptsLoads = true
  }

  mutating func end() {
    guard acceptsLoads else { return }
    acceptsLoads = false
    generation &+= 1
  }
}
