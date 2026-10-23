import MC1Services
import Testing

@Suite("Remote CLI no-reply commands")
struct RemoteCLINoReplyCommandTests {
  @Test(arguments: ["reboot", "reboot now", "Reboot", "clkreboot"])
  func `reboot verbs match`(command: String) {
    #expect(RemoteCLINoReplyCommand(command) == .reboot)
  }

  @Test(arguments: ["poweroff", "shutdown", " SHUTDOWN "])
  func `power off verbs match`(command: String) {
    #expect(RemoteCLINoReplyCommand(command) == .powerOff)
  }

  @Test(arguments: ["rebooted", "poweroffx", "get reboot", ""])
  func `other text does not match`(command: String) {
    #expect(RemoteCLINoReplyCommand(command) == nil)
  }
}
