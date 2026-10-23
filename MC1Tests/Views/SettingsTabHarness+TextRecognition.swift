import UIKit
import Vision

extension SettingsTabHarness {
  func matchingRect(in view: UIView, target: String) -> CGRect? {
    let guided = rects(matching: target, in: recognizedWords(in: view, vocabulary: [target]))
    if let rect = guided.min(by: { rectArea($0) < rectArea($1) }) { return rect }
    let plain = rects(matching: target, in: recognizedWords(in: view, vocabulary: []))
    return plain.min { rectArea($0) < rectArea($1) }
  }

  func windowRects(matching title: String) -> [CGRect] {
    rects(matching: title, in: recognizedWords(in: window, vocabulary: [title]))
      + rects(matching: title, in: recognizedWords(in: window, vocabulary: []))
  }

  private func rects(matching target: String, in words: [RecognizedWord]) -> [CGRect] {
    let ordered = words.sorted { lhs, rhs in
      if abs(lhs.rect.minY - rhs.rect.minY) > 4 { return lhs.rect.minY < rhs.rect.minY }
      return lhs.rect.minX < rhs.rect.minX
    }
    var found = ordered.filter { matchesLabel($0.text, target: target) }.map(\.rect)
    for start in ordered.indices {
      var text = ""
      var rect = CGRect.null
      let origin = ordered[start].rect
      for word in ordered[start...] {
        if !rect.isNull, !sameLine(origin, word.rect) { break }
        text = text.isEmpty ? word.text : text + " " + word.text
        rect = rect.union(word.rect)
        if matchesLabel(text, target: target) {
          found.append(rect)
        }
        if text.count > target.count + 12 { break }
      }
    }
    return found
  }

  private func sameLine(_ lhs: CGRect, _ rhs: CGRect) -> Bool {
    let overlap = min(lhs.maxY, rhs.maxY) - max(lhs.minY, rhs.minY)
    let minHeight = min(lhs.height, rhs.height)
    return minHeight > 0 && overlap > minHeight * 0.4
  }

  func rectArea(_ rect: CGRect) -> CGFloat {
    max(0, rect.width) * max(0, rect.height)
  }

  func matchesLabel(_ candidate: String?, target: String) -> Bool {
    guard let candidate else { return false }
    let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
    if matchesCore(trimmed, target: target) { return true }
    let stripped = trimmed.drop { !$0.isLetter && !$0.isNumber }
    return matchesCore(String(stripped), target: target)
  }

  private func matchesCore(_ text: String, target: String) -> Bool {
    // Section headers can render in uppercase without changing their localized text.
    let text = text.folding(options: .caseInsensitive, locale: .current)
    let target = target.folding(options: .caseInsensitive, locale: .current)
    if text == target { return true }
    if text.hasPrefix(target + ",") || text.hasPrefix(target + "\n") || text.hasPrefix(target + " ") { return true }
    return false
  }

  func labels(in view: UIView) -> [String] {
    var found: [String] = []
    func walk(_ current: UIView) {
      if current !== view, current.isHidden || current.alpha == 0 || current.window !== window { return }
      appendText(from: current, to: &found)
      current.subviews.forEach(walk)
    }
    walk(view)
    return found
  }

  private func appendText(from object: NSObject, to found: inout [String]) {
    if let label = object.accessibilityLabel, !label.isEmpty {
      found.append(label)
    }
    guard let view = object as? UIView else { return }
    if let text = (view as? UILabel)?.text, !text.isEmpty {
      found.append(text)
    }
    if let button = view as? UIButton {
      if let title = button.currentTitle, !title.isEmpty { found.append(title) }
      if let title = button.configuration?.title, !title.isEmpty { found.append(title) }
    }
    if let cell = view as? UICollectionViewCell {
      appendListText(cell.contentConfiguration, to: &found)
    } else if let row = view as? UITableViewCell {
      appendListText(row.contentConfiguration, to: &found)
    }
    let count = view.accessibilityElementCount()
    if count != NSNotFound, count > 0 {
      for index in 0..<count {
        guard let element = view.accessibilityElement(at: index) as? NSObject, element !== view else { continue }
        if let label = element.accessibilityLabel, !label.isEmpty {
          found.append(label)
        }
      }
    }
  }

  private func appendListText(_ configuration: UIContentConfiguration?, to found: inout [String]) {
    guard let list = configuration as? UIListContentConfiguration else { return }
    for value in [list.text, list.secondaryText, list.attributedText?.string, list.secondaryAttributedText?.string] {
      if let value, !value.isEmpty { found.append(value) }
    }
  }

  /// Read visible tab controls because item titles can exist offscreen.
  func controlLabels(in bar: UITabBar) -> [String] {
    var found: [String] = []
    func walk(_ view: UIView) {
      if let control = view as? UIControl {
        if viewIsVisible(control), control.isUserInteractionEnabled, control.isEnabled {
          found.append(contentsOf: labels(in: view))
        }
        return
      }
      view.subviews.forEach(walk)
    }
    walk(bar)
    return found
  }

  private func recognizedWords(in view: UIView, vocabulary: [String]) -> [RecognizedWord] {
    let key = recognitionKey(for: view, vocabulary: vocabulary)
    if let cache = recognitionCache, cache.stamp == recognitionStamp, cache.key == key {
      return cache.words
    }
    let words = recognizeText(in: view, vocabulary: vocabulary)
    recognitionCache = (recognitionStamp, key, words)
    return words
  }

  private func recognitionKey(for view: UIView, vocabulary: [String]) -> String {
    let offset = if let list = view as? UICollectionView {
      "\(list.contentOffset.x),\(list.contentOffset.y)"
    } else {
      "0"
    }
    return "\(ObjectIdentifier(view))|\(offset)|\(vocabulary.joined(separator: "|"))"
  }

  private func recognizeText(in view: UIView, vocabulary: [String]) -> [RecognizedWord] {
    guard view.bounds.width > 1, view.bounds.height > 1 else { return [] }
    let renderer = UIGraphicsImageRenderer(bounds: view.bounds)
    let image = renderer.image { context in
      view.layer.render(in: context.cgContext)
    }
    guard let cgImage = image.cgImage else { return [] }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = false
    request.minimumTextHeight = Recognition.minimumRelativeHeight
    if !vocabulary.isEmpty {
      request.customWords = vocabulary
    }
    try? VNImageRequestHandler(cgImage: cgImage, options: [:]).perform([request])
    let width = view.bounds.width
    let height = view.bounds.height
    return request.results?.compactMap { observation -> RecognizedWord? in
      guard let text = observation.topCandidates(1).first?.string, !text.isEmpty else { return nil }
      let box = observation.boundingBox
      let rect = CGRect(
        x: box.minX * width,
        y: (1 - box.maxY) * height,
        width: box.width * width,
        height: box.height * height
      )
      return RecognizedWord(text: text, rect: rect)
    } ?? []
  }
}
