import Foundation
import MC1Services
import Observation

/// In-memory pixels for one conversation's Pixel Paint cover.
@Observable
@MainActor
final class PixelPaintDraft {
  static let side = 16
  static let profile = 2
  static let background = 0
  static let initialBrush = 2
  /// Spoken names in `profile` palette order.
  private static let colorNames = [
    L10n.Chats.Chats.Mcoimg.ColorName.white, L10n.Chats.Chats.Mcoimg.ColorName.gray,
    L10n.Chats.Chats.Mcoimg.ColorName.black, L10n.Chats.Chats.Mcoimg.ColorName.red,
    L10n.Chats.Chats.Mcoimg.ColorName.yellow, L10n.Chats.Chats.Mcoimg.ColorName.green,
    L10n.Chats.Chats.Mcoimg.ColorName.blue, L10n.Chats.Chats.Mcoimg.ColorName.purple,
  ]

  static func colorName(_ index: Int) -> String {
    colorNames.indices.contains(index) ? colorNames[index] : ""
  }

  var pixels = Array(repeating: background, count: side * side)
  var selectedColor = initialBrush
  var tool = PixelPaintTool.brush
  var undoStack: [[Int]] = []
  var redoStack: [[Int]] = []
  /// Changes only when `paint` changes a cell, so the stroke haptic stays silent on undo, fill, and clear.
  var paintedCellRevision = 0
  var encodedByteCount: Int?
  var isSubmitting = false
  /// True once this submission has called `onSend`. Cancel stays enabled until then.
  var isAdmitting = false
  var errorTitle: String?
  var errorMessage: String?
  /// `strokeStart` becomes one undo entry when `endStroke` runs.
  var previousCell: (x: Int, y: Int)?
  var strokeStart: [Int]?
  private var submissionGeneration: UInt = 0
  private var submissionTask: Task<Void, Never>?

  var image: MCOImageRaster {
    MCOImageRaster(width: Self.side, height: Self.side, profile: Self.profile, pixels: pixels)
  }

  /// Drops the current pixels and retires the in-flight submission.
  func remove() {
    submissionGeneration &+= 1
    submissionTask?.cancel()
    submissionTask = nil
    resetPixels()
    isSubmitting = false
    isAdmitting = false
    errorTitle = nil
    errorMessage = nil
  }

  /// Pixels and generation captured when a submission starts. Painting is already frozen.
  struct Attempt {
    let snapshot: MCOImageRaster
    let generation: UInt
  }

  /// Starts a submission synchronously so a second tap cannot start another one.
  /// Returns nil when one is already running or the connection is not ready.
  func begin(isReady: () -> Bool) -> Attempt? {
    guard !isSubmitting, isReady() else { return nil }
    isSubmitting = true
    isAdmitting = false
    errorTitle = nil
    errorMessage = nil
    return Attempt(snapshot: image, generation: submissionGeneration)
  }

  /// Remembers the task `remove()` cancels when the drawing is retired.
  func track(_ task: Task<Void, Never>) {
    submissionTask = task
  }

  /// Encodes `attempt` and admits it only while that generation is still current.
  func run(
    _ attempt: Attempt,
    transport: PixelPaintTransport,
    senderName: @escaping () -> String,
    isReady: @escaping () -> Bool,
    onSend: @escaping (String) async -> Bool,
    onWillSend: @escaping () -> Void,
    onAdmitted: @escaping () -> Void,
    encode: @escaping @Sendable (MCOImageRaster, PixelPaintTransport) async throws -> String = encodePixelDrawing
  ) async {
    guard isCurrent(attempt.generation) else { return }
    let result = await outcome(
      snapshot: attempt.snapshot,
      transport: transport,
      generation: attempt.generation,
      senderName: senderName,
      isReady: isReady,
      onSend: onSend,
      onWillSend: onWillSend,
      encode: encode
    )
    settle(result, generation: attempt.generation, onAdmitted: onAdmitted)
  }

  private func outcome(
    snapshot: MCOImageRaster,
    transport: PixelPaintTransport,
    generation: UInt,
    senderName: @escaping () -> String,
    isReady: @escaping () -> Bool,
    onSend: @escaping (String) async -> Bool,
    onWillSend: @escaping () -> Void,
    encode: @escaping @Sendable (MCOImageRaster, PixelPaintTransport) async throws -> String
  ) async -> SubmissionResult {
    let text: String
    do {
      text = try await encode(snapshot, transport)
    } catch is CancellationError {
      return .retired
    } catch {
      guard isCurrent(generation) else { return .retired }
      return Self.encodeFailed
    }
    // A retired submission must not admit, and must not write pixels, the error, or submission state.
    guard isCurrent(generation) else { return .retired }
    guard isReady() else {
      return Self.unableToSend
    }
    let bytes: Int
    do {
      bytes = try transport.encodedByteCount(text: text, senderName: senderName())
    } catch {
      guard isCurrent(generation) else { return .retired }
      return Self.encodeFailed
    }
    guard isCurrent(generation) else { return .retired }
    let limit = transport.byteLimit
    guard bytes <= limit else {
      return .failed(
        title: L10n.Chats.Chats.Mcoimg.tooLargeTitle,
        message: transport.isChannel
          ? L10n.Chats.Chats.Mcoimg.tooLargeChannel(bytes, limit)
          : L10n.Chats.Chats.Mcoimg.tooLarge(bytes, limit)
      )
    }
    guard isCurrent(generation) else { return .retired }
    isAdmitting = true
    onWillSend()
    let accepted = await onSend(text)
    guard isCurrent(generation) else { return .retired }
    return accepted ? .admitted : .rejected
  }

  private func settle(
    _ result: SubmissionResult,
    generation: UInt,
    onAdmitted: () -> Void
  ) {
    guard isCurrent(generation) else { return }
    switch result {
    case .admitted:
      remove()
      onAdmitted()
    case .rejected:
      errorTitle = L10n.Chats.Chats.Alert.UnableToSend.title
      errorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
      isSubmitting = false
      isAdmitting = false
    case let .failed(title, message):
      errorTitle = title
      errorMessage = message
      isSubmitting = false
      isAdmitting = false
    case .retired:
      break
    }
  }

  private func isCurrent(_ generation: UInt) -> Bool {
    submissionGeneration == generation && !Task.isCancelled
  }

  private static var unableToSend: SubmissionResult {
    .failed(
      title: L10n.Chats.Chats.Alert.UnableToSend.title,
      message: L10n.Chats.Chats.Alert.UnableToSend.message
    )
  }

  private static var encodeFailed: SubmissionResult {
    .failed(
      title: L10n.Chats.Chats.Mcoimg.encodingFailedTitle,
      message: L10n.Chats.Chats.Mcoimg.encodingFailed
    )
  }

  private enum SubmissionResult {
    case admitted
    case rejected
    case failed(title: String, message: String)
    case retired
  }
}

@concurrent
func encodePixelDrawing(_ image: MCOImageRaster, transport: PixelPaintTransport) async throws -> String {
  let body = try MCOImageV3Codec.encode(image, nonce: UInt8.random(in: .min ... .max))
  let payload = transport == .channelData
    ? try Data([MCOImageCodec.v4Subtype]) + MCOImageV4Codec.wrappingV3Body(body)
    : Data([MCOImageCodec.v3Subtype]) + body
  return try MCOImageCodec.text(fromAppPayload: payload)
}
