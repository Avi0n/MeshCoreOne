import Foundation

public enum DirectMessageAdmission: Sendable {
  case discarded
  case replay(MessageDTO)
  case reaction(ContactDTO)
  case saved(ContactDTO?)
}
