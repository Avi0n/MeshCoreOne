import Foundation
@testable import MeshCore
import Testing

@Suite("Log privacy")
struct LogPrivacyTests {
  @Test
  func `Default privacy shows numbers and redacts strings`() {
    #expect(MeshCoreLogRendering.render("radio", privacy: .public) == "radio")
    #expect(MeshCoreLogRendering.render("radio", privacy: .private) == "<private>")
    #expect(MeshCoreLogRendering.render("radio", privacy: .sensitive) == "<private>")
    #expect(MeshCoreLogRendering.render("radio", privacy: .auto) == "<private>")
    #expect(MeshCoreLogRendering.render(7, privacy: .auto) == "7")
    #expect(MeshCoreLogRendering.render(7, privacy: .private) == "<private>")
    #expect(MeshCoreLogRendering.render(true, privacy: .auto) == "true")
    #expect(MeshCoreLogRendering.render(UInt(3), privacy: .auto) == "3")
    #expect(MeshCoreLogRendering.render(1.5, privacy: .auto) == "1.5")
  }
}
