import MC1Services
import Testing
@testable import MC1

@Suite("Pixel paint drafts")
@MainActor
struct PixelPaintDraftTests {
  @Test func paintingInterpolatesAcrossTheCanvas() {
    let draft = PixelPaintDraft()
    draft.paint(x: 0, y: 0)
    draft.paint(x: PixelPaintDraft.side - 1, y: PixelPaintDraft.side - 1)
    draft.endStroke()
    let painted = draft.image
    for index in 0..<PixelPaintDraft.side {
      #expect(painted.pixels[index * PixelPaintDraft.side + index] == PixelPaintDraft.initialBrush)
    }
    draft.endStroke()
    #expect(draft.image == painted)
  }

  @Test func blankCanvasIsAdmittedOnceAndClears() async throws {
    let draft = PixelPaintDraft()
    let probe = AdmissionProbe()
    await probe.submit(draft)?.value
    #expect(probe.payloads.count == 1)
    #expect(probe.admitted == 1)
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
  }

  @Test func paintAndClearDuringSubmitDoNotChangeTheCapturedRaster() async throws {
    let draft = PixelPaintDraft()
    draft.paint(x: 1, y: 2)
    draft.endStroke()
    let snapshot = draft.image
    let probe = AdmissionProbe()
    probe.pauseOnSend = true
    let task = probe.submit(draft)
    await probe.pause.untilPaused()
    let second = probe.submit(draft)
    draft.paint(x: 3, y: 4)
    draft.clear()
    #expect(second == nil)
    #expect(probe.payloads.count == 1)
    #expect(probe.captured == [snapshot])
    #expect(draft.image == snapshot)
    #expect(draft.isAdmitting)
    probe.pause.resume()
    await task?.value
    #expect(probe.payloads.count == 1)
    #expect(probe.admitted == 1)
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
  }

  @Test func cancelBeforeAdmissionSendsNothingAndLeavesABlankCanvas() async throws {
    let draft = PixelPaintDraft()
    draft.paint(x: 4, y: 5)
    draft.endStroke()
    let probe = AdmissionProbe()
    probe.pauseOnEncode = true
    let task = probe.submit(draft)
    await probe.pause.untilPaused()
    #expect(!draft.isAdmitting)
    draft.paint(x: 0, y: 0)
    draft.clear()
    draft.remove()
    probe.pause.resume()
    await task?.value
    #expect(probe.payloads.isEmpty)
    #expect(probe.admitted == 0)
    #expect(probe.rejected == 0)
    #expect(draft.errorTitle == nil)
    #expect(draft.errorMessage == nil)
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
    #expect(!draft.isSubmitting)
  }

  @Test func rejectedAdmissionKeepsPixelsAndClearsTheConversationSendError() async throws {
    let draft = PixelPaintDraft()
    draft.paint(x: 2, y: 3)
    draft.endStroke()
    let snapshot = draft.image
    let probe = AdmissionProbe()
    probe.accept = false
    await probe.submit(draft)?.value
    #expect(probe.payloads.count == 1)
    #expect(probe.rejected == 1)
    #expect(probe.sendErrorMessage == nil)
    #expect(draft.errorTitle == L10n.Chats.Chats.Alert.UnableToSend.title)
    #expect(draft.errorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    #expect(draft.image == snapshot)
    #expect(!draft.isSubmitting)
    #expect(!draft.isAdmitting)
    draft.clear()
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
  }

  @Test func disconnectAfterTheTapKeepsPixelsAndDoesNotAdmit() async throws {
    let draft = PixelPaintDraft()
    draft.paint(x: 6, y: 1)
    draft.endStroke()
    let snapshot = draft.image
    let probe = AdmissionProbe()
    probe.pauseOnEncode = true
    probe.sendErrorMessage = nil
    let task = probe.submit(draft)
    await probe.pause.untilPaused()
    probe.ready = false
    probe.pause.resume()
    await task?.value
    #expect(probe.payloads.isEmpty)
    #expect(probe.rejected == 0)
    #expect(probe.sendErrorMessage == nil)
    #expect(draft.errorTitle == L10n.Chats.Chats.Alert.UnableToSend.title)
    #expect(draft.errorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
    #expect(draft.image == snapshot)
    #expect(!draft.isAdmitting)
  }

  @Test func encodeFailureKeepsPixelsAndSetsTheEncodeTitle() async throws {
    let draft = PixelPaintDraft()
    draft.paint(x: 1, y: 1)
    draft.endStroke()
    let snapshot = draft.image
    let probe = AdmissionProbe()
    probe.failEncode = true
    await probe.submit(draft)?.value
    #expect(probe.payloads.isEmpty)
    #expect(probe.rejected == 0)
    #expect(draft.errorTitle == L10n.Chats.Chats.Mcoimg.encodingFailedTitle)
    #expect(draft.errorMessage == L10n.Chats.Chats.Mcoimg.encodingFailed)
    #expect(draft.image == snapshot)
    #expect(!draft.isSubmitting)
  }

  @Test func oversizedPayloadKeepsPixelsAndSetsTheSizeTitle() async throws {
    let draft = PixelPaintDraft()
    draft.paint(x: 2, y: 2)
    draft.endStroke()
    let snapshot = draft.image
    let probe = AdmissionProbe()
    probe.oversized = true
    await probe.submit(draft)?.value
    let count = ProtocolLimits.maxDirectMessageLength + 1
    #expect(probe.payloads.isEmpty)
    #expect(draft.errorTitle == L10n.Chats.Chats.Mcoimg.tooLargeTitle)
    #expect(draft.errorMessage == L10n.Chats.Chats.Mcoimg.tooLarge(count, ProtocolLimits.maxDirectMessageLength))
    #expect(draft.image == snapshot)
    #expect(!draft.isSubmitting)
  }

  @Test func canvasEdgesDoNotWrapIntoOtherRows() {
    let draft = PixelPaintDraft()
    draft.paint(x: PixelPaintDraft.side, y: 0)
    draft.paint(x: -1, y: 0)
    draft.paint(x: 0, y: PixelPaintDraft.side)
    #expect(draft.image.pixels.allSatisfy { $0 == PixelPaintDraft.background })
  }
}

@MainActor
private final class AdmissionProbe {
  var payloads: [String] = []
  var captured: [MCOImageRaster] = []
  var rejected = 0
  var admitted = 0
  var ready = true
  var accept = true
  var pauseOnEncode = false
  var pauseOnSend = false
  var failEncode = false
  var oversized = false
  var sendErrorMessage: String?
  let pause = SubmissionPause()

  func submit(_ draft: PixelPaintDraft) -> Task<Void, Never>? {
    guard let attempt = draft.begin(isReady: { self.ready }) else { return nil }
    let pause = pause
    let pauseOnEncode = pauseOnEncode
    let pauseOnSend = pauseOnSend
    let failEncode = failEncode
    let oversized = oversized
    let task = Task {
      await draft.run(
        attempt,
        isChannel: false,
        senderName: { "Radio" },
        isReady: { self.ready },
        onSend: { text in
          self.payloads.append(text)
          if pauseOnSend { await pause.pause() }
          if !self.accept {
            self.sendErrorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
          }
          return self.accept
        },
        onWillSend: {},
        onRejected: {
          self.rejected += 1
          self.sendErrorMessage = nil
        },
        onAdmitted: { self.admitted += 1 },
        encode: { image, isChannel in
          await MainActor.run { self.captured.append(image) }
          if pauseOnEncode { await pause.pause() }
          if failEncode { throw PixelEncodeFailure() }
          if oversized {
            return String(repeating: "a", count: ProtocolLimits.maxDirectMessageLength + 1)
          }
          return try await encodePixelDrawing(image, channel: isChannel)
        }
      )
    }
    draft.track(task)
    return task
  }
}

private struct PixelEncodeFailure: Error {}

@MainActor
final class SubmissionPause {
  private var release: CheckedContinuation<Void, Never>?
  private var entered: CheckedContinuation<Void, Never>?

  func pause() async {
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      release = continuation
      entered?.resume()
      entered = nil
    }
  }

  func untilPaused() async {
    if release != nil { return }
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      if release != nil {
        continuation.resume()
      } else {
        entered = continuation
      }
    }
  }

  func resume() {
    release?.resume()
    release = nil
  }
}
