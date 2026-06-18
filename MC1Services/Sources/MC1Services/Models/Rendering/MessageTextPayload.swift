import Foundation

public struct MessageTextPayload: Sendable, Hashable {
    public let raw: String
    public let formatted: AttributedString?
    public let baseColor: BaseColorSlot
    public let isOutgoing: Bool
    public let currentUserName: String

    public init(
        raw: String,
        formatted: AttributedString?,
        baseColor: BaseColorSlot,
        isOutgoing: Bool,
        currentUserName: String
    ) {
        self.raw = raw
        self.formatted = formatted
        self.baseColor = baseColor
        self.isOutgoing = isOutgoing
        self.currentUserName = currentUserName
    }
}
