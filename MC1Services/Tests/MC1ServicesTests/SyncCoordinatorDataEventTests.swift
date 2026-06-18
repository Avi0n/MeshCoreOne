import Foundation
import Testing
@testable import MC1Services

@Suite("SyncCoordinator data events")
struct SyncCoordinatorDataEventTests {

    @Test("two subscribers both observe contactsChanged after notifyContactsChanged")
    @MainActor
    func twoSubscribersObserveContactsChanged() async {
        let coordinator = SyncCoordinator()
        let streamA = coordinator.dataEvents()
        let streamB = coordinator.dataEvents()

        coordinator.notifyContactsChanged()
        coordinator.notifyConversationsChanged()
        coordinator.finishDataEvents()

        var eventsA: [SyncDataEvent] = []
        for await event in streamA {
            eventsA.append(event)
        }
        var eventsB: [SyncDataEvent] = []
        for await event in streamB {
            eventsB.append(event)
        }

        #expect(eventsA.count == 2)
        #expect(eventsB.count == 2)
        for events in [eventsA, eventsB] {
            guard events.count == 2 else { continue }
            guard case .contactsChanged = events[0] else {
                Issue.record("Expected first event to be contactsChanged, got \(events[0])")
                continue
            }
            guard case .conversationsChanged = events[1] else {
                Issue.record("Expected second event to be conversationsChanged, got \(events[1])")
                continue
            }
        }
        #expect(coordinator.contactsVersion == 1)
        #expect(coordinator.conversationsVersion == 1)
    }

    @Test("finishDataEvents ends every subscriber's iteration")
    func finishEndsSubscriberIteration() async {
        let coordinator = SyncCoordinator()
        let streamA = coordinator.dataEvents()
        let streamB = coordinator.dataEvents()

        let consumerA = Task {
            for await _ in streamA {}
            return true
        }
        let consumerB = Task {
            for await _ in streamB {}
            return true
        }

        coordinator.finishDataEvents()

        #expect(await consumerA.value)
        #expect(await consumerB.value)
        #expect(coordinator.dataEventBroadcaster.subscriberCount == 0)
    }
}
