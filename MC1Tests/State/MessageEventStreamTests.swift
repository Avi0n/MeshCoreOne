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
        let event = MessageEvent.messageStatusUpdated(ackCode: 0xDEAD_BEEF)

        // Subscribe and pull one event. `events()` registers the continuation
        // synchronously, so by the time the child task suspends on next(), the
        // subscriber is wired.
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

        // Give both child tasks a chance to register before broadcasting.
        await Task.yield()
        await Task.yield()
        #expect(stream.subscriberCount() == 2)

        stream.send(event)

        #expect(await firstReceived.value == event)
        #expect(await secondReceived.value == event)
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

    @Test("send(_:) opportunistically prunes terminated slots")
    func opportunisticPruneAfterCancellation() async {
        let stream = MessageEventStream()

        let consumerTask = Task { @MainActor in
            for await _ in stream.events() { /* run until cancelled */ }
        }

        await Task.yield()
        #expect(stream.subscriberCount() == 1)

        consumerTask.cancel()
        _ = await consumerTask.value

        // The `onTermination` callback hops to main via a Task and may not have
        // landed yet. A subsequent send detects the terminated continuation
        // (yield returns .terminated) and prunes the slot synchronously on the
        // main actor — exercising the opportunistic-prune branch in send(_:).
        stream.send(.messageFailed(messageID: UUID()))
        #expect(stream.subscriberCount() == 0)
    }

    @Test("Subscribers added after a send do not receive prior events")
    func subscribersAreNotReplayed() async {
        let stream = MessageEventStream()
        let earlyEvent = MessageEvent.messageStatusUpdated(ackCode: 1)
        let lateEvent = MessageEvent.messageStatusUpdated(ackCode: 2)

        // No subscriber — this send goes to nobody.
        stream.send(earlyEvent)

        let received = Task { @MainActor () -> MessageEvent? in
            var iterator = stream.events().makeAsyncIterator()
            return await iterator.next()
        }
        await Task.yield()
        stream.send(lateEvent)

        #expect(await received.value == lateEvent)
    }
}
