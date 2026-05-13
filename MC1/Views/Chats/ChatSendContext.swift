import Foundation
import MC1Services

/// Mutable holder for services the chat send queues read on each drain step.
/// A single instance lives for the view-model's lifetime; `configure*()`
/// updates the fields in place so a BLE reconnect rebinds services without
/// recreating the queues. The send closures capture this box by reference,
/// decoupling the queue actor's lifecycle from the view model's.
@MainActor
final class ChatSendContext {
    var dataStore: DataStore?
    var messageService: MessageService?
    var reactionService: ReactionService?
}
