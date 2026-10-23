import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Contact details confirmed favorite state")
@MainActor
struct ContactDetailFavoriteStateTests {
  @Test
  func `unavailable service leaves favorite unchanged and reports failure`() async {
    let initialFavorite = false
    let contact = makeContact(isFavorite: initialFavorite)
    let state = ContactDetailState(contact: contact)
    defer { state.end() }

    state.isFavorite = !initialFavorite
    await state.favoriteTask?.value

    #expect(state.isFavorite == initialFavorite)
    #expect(state.errorMessage != nil)
  }

  @Test(arguments: [false, true], [false, true])
  func `favorite waits for confirmation and ignores repeat requests`(
    initialFavorite: Bool,
    succeeds: Bool
  ) async throws {
    let state = ContactDetailState(contact: makeContact(isFavorite: initialFavorite))
    var response: CheckedContinuation<Void, Error>?
    var calls = 0
    defer {
      response?.resume(throwing: CancellationError())
      state.end()
    }
    state.beforeCommandForTesting = { operation in
      #expect(operation == .favorite)
      calls += 1
      try await withCheckedThrowingContinuation { response = $0 }
    }

    state.isFavorite = !initialFavorite
    let task = try #require(state.favoriteTask)
    #expect(state.isSettingFavorite)
    try await waitUntil { response != nil }
    #expect(state.isFavorite == initialFavorite)

    state.isFavorite = !initialFavorite
    #expect(calls == 1)
    if succeeds {
      response?.resume()
    } else {
      response?.resume(throwing: ContactServiceError.invalidResponse)
    }
    response = nil
    await task.value

    #expect(state.isFavorite == (succeeds ? !initialFavorite : initialFavorite))
    #expect((state.errorMessage != nil) == !succeeds)
    #expect(!state.isSettingFavorite)
    #expect(state.favoriteTask == nil)
  }

  private func makeContact(isFavorite: Bool) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: ProtocolLimits.publicKeySize),
      name: "Test Contact",
      typeRawValue: ContactType.chat.rawValue,
      lastHeardTimestamp: 0,
      isFavorite: isFavorite
    ))
  }
}
