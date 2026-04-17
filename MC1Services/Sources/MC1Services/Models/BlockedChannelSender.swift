import Foundation
import SwiftData

/// A channel sender name that the user has blocked.
/// Channel messages don't include sender keys, so blocking is name-based only.
@Model
public final class BlockedChannelSender {
    #Index<BlockedChannelSender>([\.radioID, \.name])

    @Attribute(.unique)
    public var id: UUID

    /// The sender name to block (matched exactly as stored)
    public var name: String

    /// Which device this block applies to
    @Attribute(originalName: "deviceID")
    public var radioID: UUID

    /// When the user blocked this name
    public var dateBlocked: Date

    public init(
        id: UUID = UUID(),
        name: String,
        radioID: UUID,
        dateBlocked: Date = .now
    ) {
        self.id = id
        self.name = name
        self.radioID = radioID
        self.dateBlocked = dateBlocked
    }

    /// Builds a model instance directly from a DTO.
    public convenience init(dto: BlockedChannelSenderDTO) {
        self.init(id: dto.id, name: dto.name, radioID: dto.radioID, dateBlocked: dto.dateBlocked)
    }
}

// MARK: - Sendable DTO

/// A sendable snapshot of BlockedChannelSender for cross-actor transfers.
public struct BlockedChannelSenderDTO: Sendable, Equatable, Identifiable, Codable {
    public let id: UUID
    public let name: String
    public var radioID: UUID
    public let dateBlocked: Date

    public init(
        id: UUID = UUID(),
        name: String,
        radioID: UUID,
        dateBlocked: Date = .now
    ) {
        self.id = id
        self.name = name
        self.radioID = radioID
        self.dateBlocked = dateBlocked
    }

    public init(from model: BlockedChannelSender) {
        self.id = model.id
        self.name = model.name
        self.radioID = model.radioID
        self.dateBlocked = model.dateBlocked
    }
}
