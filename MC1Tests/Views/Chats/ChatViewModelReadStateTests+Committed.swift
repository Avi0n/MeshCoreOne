import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ChatViewModelReadStateTests {
  @Test(arguments: Kind.allCases, [false, true])
  func `admitted save finishes its refresh before a successful or failed successor`(kind: Kind, failNewest: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    let arrived = Gate()
    let resume = Gate()
    environment.viewModel.reloadInterleaveHook = {
      await arrived.open()
      await resume.wait()
    }
    let first = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await arrived.wait()
    #expect(try await kind.unreadCount(in: environment) == 0)
    if failNewest { try await kind.delete(in: environment) }
    environment.viewModel.reloadInterleaveHook = nil
    let entered = Gate()
    let last = Task(priority: .high) {
      await entered.open()
      await environment.viewModel.setReadState(conversation, isRead: false)
    }
    await entered.wait()
    await Task.yield()
    await resume.open()
    await first.value
    await last.value

    #expect(environment.recorder.removed == ["matching"])
    #expect(environment.recorder.badges == (failNewest ? [8] : [8, 9]))
    #expect(environment.sync.conversationsVersion == (failNewest ? 1 : 2))
    #expect(try await kind.unreadCount(in: environment) == (failNewest ? nil : 1))
    if failNewest {
      #expect(environment.viewModel.errorMessage == L10n.Chats.Chats.Error.readStateSaveFailed)
    } else {
      #expect(environment.viewModel.liveConversation(id: conversation.id)?.unreadCount == 1)
      #expect(environment.viewModel.errorMessage == nil)
    }
  }

  @Test(arguments: Kind.allCases)
  func `superseded queued command preserves its admitted predecessor`(kind: Kind) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    let secondWindow = environment.makeViewModel()
    let thirdWindow = environment.makeViewModel()
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    let firstArrived = Gate()
    let firstResume = Gate()
    environment.viewModel.reloadInterleaveHook = {
      await firstArrived.open()
      await firstResume.wait()
    }
    let first = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await firstArrived.wait()
    let secondArrived = Gate()
    let secondResume = Gate()
    secondWindow.readStateBeforePredecessorInterleaveHook = {
      await secondArrived.open()
      await secondResume.wait()
    }
    let second = Task {
      await secondWindow.setReadState(conversation, isRead: true)
      #expect(environment.recorder.badges.contains(8))
    }
    await secondArrived.wait()
    let thirdArrived = Gate()
    let thirdResume = Gate()
    environment.recorder.snapshotHook = {
      await thirdArrived.open()
      await thirdResume.wait()
    }
    let third = Task { await thirdWindow.setReadState(conversation, isRead: true) }
    await thirdArrived.wait()
    await thirdResume.open()
    await secondResume.open()
    await firstResume.open()
    await first.value
    await second.value
    await third.value

    #expect(try await kind.unreadCount(in: environment) == 0)
    #expect(environment.recorder.badges == [8, 8])
    #expect(environment.sync.conversationsVersion == 2)
    #expect(environment.recorder.removed == ["matching"])
  }
}
