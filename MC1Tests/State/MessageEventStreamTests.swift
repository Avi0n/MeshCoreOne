import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("MessageEventStream")
@MainActor
struct MessageEventStreamTests {

    @Test("Single consumer receives an event sent via send(_:)")
    func singleConsumerReceivesEvent() async {
        let stream = MessageEventStream()
        let event = MessageEvent.messageStatusResolved(messageID: UUID(), status: .sent)

        let received = Task { @MainActor () -> MessageEvent? in
            var iterator = stream.events().makeAsyncIterator()
            return await iterator.next()
        }

        await Task.yield()
        stream.send(event)

        #expect(await received.value == event)
    }

    @Test("Multiple consumers each receive the same event")
    func multipleConsumersBothReceive() async {
        let stream = MessageEventStream()
        let event = MessageEvent.heardRepeatRecorded(messageID: UUID(), count: 3)

        let firstReceived = Task { @MainActor () -> MessageEvent? in
            var iterator = stream.events().makeAsyncIterator()
            return await iterator.next()
        }
        let secondReceived = Task { @MainActor () -> MessageEvent? in
            var iterator = stream.events().makeAsyncIterator()
            return await iterator.next()
        }

        await Task.yield()
        await Task.yield()
        #expect(stream.subscriberCount() == 2)

        stream.send(event)

        #expect(await firstReceived.value == event)
        #expect(await secondReceived.value == event)
    }

    @Test("Events sent across consumer task restarts are not dropped when the consumer is held by a long-lived task")
    func longLivedConsumer_DoesNotDropEventsAcrossSimulatedReconnect() async {
        let stream = MessageEventStream()
        let event1 = MessageEvent.messageStatusResolved(messageID: UUID(), status: .sent)
        let event2 = MessageEvent.messageStatusResolved(messageID: UUID(), status: .delivered)

        actor Collector {
            var items: [MessageEvent] = []
            func append(_ value: MessageEvent) { items.append(value) }
            func snapshot() -> [MessageEvent] { items }
        }
        let received = Collector()

        let consumer = Task { @MainActor in
            for await event in stream.events() {
                await received.append(event)
                if await received.snapshot().count == 2 { return }
            }
        }

        await Task.yield()
        stream.send(event1)
        stream.send(event2)

        _ = await consumer.value
        let collected = await received.snapshot()
        #expect(collected.count == 2)
        #expect(collected[0] == event1)
        #expect(collected[1] == event2)
    }

    @Test("subscriberCount reflects active subscriptions")
    func subscriberCountTracksRegistration() async {
        let stream = MessageEventStream()
        #expect(stream.subscriberCount() == 0)

        let firstTask = Task { @MainActor in
            for await _ in stream.events() { /* hold subscription */ }
        }
        await Task.yield()
        #expect(stream.subscriberCount() == 1)

        let secondTask = Task { @MainActor in
            for await _ in stream.events() { /* hold subscription */ }
        }
        await Task.yield()
        #expect(stream.subscriberCount() == 2)

        firstTask.cancel()
        secondTask.cancel()
        _ = await firstTask.value
        _ = await secondTask.value

        // The onTermination hops to main via a Task; pump a send through
        // to force the opportunistic-prune branch to run. After the pump,
        // both terminated slots should be removed.
        stream.send(.messageFailed(messageID: UUID()))
        #expect(stream.subscriberCount() == 0)
    }
}
