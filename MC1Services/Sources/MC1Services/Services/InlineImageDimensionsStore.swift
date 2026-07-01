import CoreGraphics
import Foundation
import os
import OSLog

/// Persists inline-image aspect ratios to a flat JSON file in Application Support.
///
/// The store is disposable: if the on-disk file is missing or corrupt, the actor
/// starts empty and re-populates via probe-on-next-receive. It is intentionally
/// not part of any backup envelope and not versioned.
///
/// `aspect(for:)` is `nonisolated` and wait-free, reading from an
/// `OSAllocatedUnfairLock` mirror so SwiftUI view bodies can resolve frame
/// heights without awaiting an actor hop.
public actor InlineImageDimensionsStore {
  private struct Entry: Codable {
    let aspect: Double
    let fetchedAt: Date
  }

  private static let storeFilename = "InlineImageDimensions.json"
  private static let resolutionStreamBufferDepth = 64

  private let logger = Logger(subsystem: "com.mc1", category: "InlineImageDimensionsStore")

  private let fileURL: URL
  private var entries: [String: Entry] = [:]
  private let aspectMirror: OSAllocatedUnfairLock<[String: Double]>
  private let streamContinuation: AsyncStream<URL>.Continuation
  private let stream: AsyncStream<URL>

  /// Production initializer using the default Application Support path.
  public init() {
    self.init(fileURL: nil)
  }

  /// Designated initializer. Pass a custom `fileURL` for tests; production callers
  /// should use `init()` which resolves the Application Support path.
  public init(fileURL: URL?) {
    let resolvedURL = fileURL ?? Self.defaultFileURL()
    self.fileURL = resolvedURL

    let (stream, continuation) = AsyncStream.makeStream(
      of: URL.self,
      bufferingPolicy: .bufferingOldest(Self.resolutionStreamBufferDepth)
    )
    self.stream = stream
    streamContinuation = continuation

    let directory = resolvedURL.deletingLastPathComponent()
    do {
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    } catch {
      logger.info("Could not create directory for inline image dimensions store: \(error.localizedDescription, privacy: .public)")
    }

    var loaded: [String: Entry] = [:]
    if let data = try? Data(contentsOf: resolvedURL) {
      if let decoded = try? JSONDecoder().decode([String: Entry].self, from: data) {
        loaded = decoded
      } else {
        logger.info("Inline image dimensions file present but undecodable; starting empty")
      }
    }
    entries = loaded

    let aspects = loaded.mapValues(\.aspect)
    aspectMirror = OSAllocatedUnfairLock(initialState: aspects)
  }

  /// Upsert an aspect ratio for a URL. Saves with `width <= 0` or `height <= 0`
  /// are rejected silently and do not emit on the stream.
  public func save(url: URL, size: CGSize) async {
    guard size.width > 0, size.height > 0 else { return }

    let aspect = Double(size.width) / Double(size.height)
    let key = url.absoluteString
    let entry = Entry(aspect: aspect, fetchedAt: Date())

    entries[key] = entry
    aspectMirror.withLock { $0[key] = aspect }
    streamContinuation.yield(url)

    persist()
  }

  /// Nonisolated wait-free aspect lookup. Safe to call from SwiftUI view bodies.
  public nonisolated func aspect(for url: URL) -> Double? {
    aspectMirror.withLock { $0[url.absoluteString] }
  }

  /// Broadcast stream of URLs whose aspect was just (re)saved. Emits on every
  /// save call, including idempotent re-saves where the aspect did not change.
  ///
  /// Single-consumer by design; `ChatViewModel` owns this in non-split-view
  /// contexts. In iPad split view (multiple `ChatViewModel`s alive at once),
  /// events are delivered to whichever subscriber happens to be iterating
  /// first; affected bubbles still rebuild via other triggers (visible-cell
  /// reload, retry, manual `rebuildDisplayItem`).
  public nonisolated var resolutionStream: AsyncStream<URL> {
    stream
  }

  private func persist() {
    guard let data = try? JSONEncoder().encode(entries) else {
      logger.error("Failed to encode inline image dimensions snapshot")
      return
    }
    do {
      try data.write(to: fileURL, options: .atomic)
    } catch {
      logger.error("Failed to write inline image dimensions store: \(error.localizedDescription, privacy: .public)")
    }
  }

  private static func defaultFileURL() -> URL {
    let appSupport = FileManager.default
      .urls(for: .applicationSupportDirectory, in: .userDomainMask)
      .first!
    return appSupport.appending(path: storeFilename)
  }
}
