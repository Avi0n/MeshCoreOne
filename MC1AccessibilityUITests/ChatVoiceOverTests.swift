import XCTest

@MainActor
final class ChatVoiceOverTests: XCTestCase {
  private static let rotorTransitionTimeout: TimeInterval = 5

  func testDateAndMessageSpeechOrder() throws {
    try withTranscript { app, voiceOver in
      let firstDate = app.staticTexts["September 28, 2026"]
      XCTAssertTrue(firstDate.waitForExistence(timeout: 10))
      firstDate.tap()
      var utterances = try [voiceOver.currentSpeech().utterance]
      for _ in 0..<40 {
        if utterances.last?.contains("Fixture message 13") == true { break }
        try utterances.append(voiceOver.moveForward().utterance)
      }
      attachSpeech(utterances.joined(separator: "\n"), name: "Transcript VoiceOver speech")

      XCTAssertEqual(utterances.count, 16)
      XCTAssertEqual(utterances.filter { $0.contains("September 28, 2026") }.count, 1)
      XCTAssertEqual(utterances.filter { $0.contains("September 29, 2026") }.count, 1)
      XCTAssertTrue(utterances.first?.localizedCaseInsensitiveContains("heading") == true)
      let messages = utterances.filter { $0.contains("Fixture message") }
      XCTAssertEqual(messages.count, 14)
      for (index, message) in messages.enumerated() {
        XCTAssertTrue(message.contains("Fixture message \(index) Button"))
      }
      let secondDate = try XCTUnwrap(utterances.firstIndex { $0.contains("September 29, 2026") })
      let messageTwelve = try XCTUnwrap(utterances.firstIndex { $0.contains("Fixture message 12") })
      XCTAssertLessThan(secondDate, messageTwelve)

      let backward = try (0..<3).map { _ in try voiceOver.moveBackward().utterance }
      attachSpeech(backward.joined(separator: "\n"), name: "Backward VoiceOver speech")
      XCTAssertTrue(backward[0].contains("Fixture message 12"))
      XCTAssertTrue(backward[1].contains("September 29, 2026"))
      XCTAssertTrue(backward[1].localizedCaseInsensitiveContains("heading"))
      XCTAssertTrue(backward[2].contains("Fixture message 11"))
    }
  }

  func testLatestMessageJumpFocus() throws {
    try verifyJump()
  }

  func testLatestMessageJumpWithoutVoiceOver() throws {
    try withTranscript(voiceOverEnabled: false) { app, _ in
      let jump = app.buttons["chat.scrollToBottom"]
      XCTAssertTrue(jump.waitForExistence(timeout: 10))
      XCTAssertTrue(jump.isHittable)
      jump.tap()

      let latest = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Fixture message 35")).firstMatch
      let positioned = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        latest.isHittable && !jump.isHittable
      }, object: nil)
      XCTAssertEqual(XCTWaiter.wait(for: [positioned], timeout: 10), .completed)
    }
  }

  func testHeadingsRotor() throws {
    try verifyHeadingsRotor(longDay: false)
  }

  func testLoadedOffscreenHeadingsBoundary() throws {
    try verifyHeadingsRotor(longDay: true)
  }

  private func verifyHeadingsRotor(longDay: Bool) throws {
    try withTranscript(longDay: longDay, verifyHeadingFocus: true) { app, voiceOver in
      let firstDate = app.staticTexts["September 28, 2026"]
      XCTAssertTrue(firstDate.waitForExistence(timeout: 10))
      firstDate.tap()
      app.typeKey(.rightArrow, modifierFlags: [.control, .option])
      var speech = try [waitForSpeech(voiceOver) { $0.contains("Fixture message 0 Button") }]
      for _ in 0..<12 {
        let previous = try voiceOver.currentSpeech().utterance
        app.typeKey(.rightArrow, modifierFlags: [.control, .option, .command])
        try speech.append(waitForSpeech(voiceOver) { $0 != previous })
        if speech.last?.localizedCaseInsensitiveContains("headings") == true { break }
      }
      attachSpeech(speech.joined(separator: "\n"), name: "Headings rotor selection")
      guard speech.last?.localizedCaseInsensitiveContains("headings") == true else {
        throw XCTSkip("Headings rotor selection was not exposed by the keyboard command.")
      }
      let firstMessageOnSecondDay = longDay ? 24 : 12
      var navigation: [String] = []
      defer { attachSpeech(navigation.joined(separator: "\n"), name: "Headings navigation boundary") }
      for _ in 0..<2 {
        try navigation.append(moveToHeading("September 29, 2026", forward: true, app: app, voiceOver: voiceOver))
        try navigation.append(moveToHeading("September 30, 2026", forward: true, app: app, voiceOver: voiceOver))
        try navigation.append(moveToHeading("September 29, 2026", forward: false, app: app, voiceOver: voiceOver))
        let firstMessage = try voiceOver.moveForward().utterance
        navigation.append(firstMessage)
        XCTAssertTrue(firstMessage.contains("Fixture message \(firstMessageOnSecondDay) Button"))
        let withinDayMessage = try voiceOver.moveForward().utterance
        navigation.append(withinDayMessage)
        XCTAssertTrue(withinDayMessage.contains("Fixture message \(firstMessageOnSecondDay + 1) Button"))
        try navigation.append(moveToHeading("September 29, 2026", forward: false, app: app, voiceOver: voiceOver))
        try navigation.append(moveToHeading("September 28, 2026", forward: false, app: app, voiceOver: voiceOver))
      }
    }
  }

  private func moveToHeading(
    _ date: String, forward: Bool, app: XCUIApplication, voiceOver: XCUIVoiceOverService
  ) throws -> String {
    app.typeKey(forward ? .downArrow : .upArrow, modifierFlags: [.control, .option])
    return try waitForSpeech(voiceOver) { utterance in
      let heading = app.staticTexts[date]
      guard utterance.contains(date), utterance.localizedCaseInsensitiveContains("heading"), heading.exists else { return false }
      let frame = heading.frame
      let nativeFocus = app.staticTexts["fixture.voiceOverFocus"].value as? String
      return nativeFocus == "\(date)|header:true|frame:true"
        && frame.width > 0 && frame.height > 0 && app.frame.intersects(frame)
    }
  }

  private func waitForSpeech(
    _ voiceOver: XCUIVoiceOverService, matching matches: @escaping (String) -> Bool,
    file: StaticString = #filePath, line: UInt = #line
  ) throws -> String {
    var samples: [String] = []
    let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
      guard let utterance = try? voiceOver.currentSpeech().utterance else { return false }
      if samples.last != utterance { samples.append(utterance) }
      return matches(utterance)
    }, object: nil)
    let result = XCTWaiter.wait(for: [settled], timeout: Self.rotorTransitionTimeout)
    if result != .completed { attachSpeech(samples.joined(separator: "\n"), name: "Unsettled VoiceOver transition") }
    return try XCTUnwrap(result == .completed ? samples.last : nil, "VoiceOver did not reach the expected element.", file: file, line: line)
  }

  private func verifyJump() throws {
    try withTranscript(verifyHeadingFocus: true) { app, voiceOver in
      let jump = app.buttons["chat.scrollToBottom"]
      XCTAssertTrue(jump.waitForExistence(timeout: 10))
      jump.tap()
      let before = try voiceOver.currentSpeech().utterance
      XCTAssertTrue(before.contains("Scroll to latest message"))
      let started = ProcessInfo.processInfo.systemUptime
      jump.doubleTap()

      var nativeFocus = ""
      let accepted = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        nativeFocus = app.staticTexts["fixture.voiceOverFocus"].value as? String ?? ""
        return nativeFocus.hasSuffix("Fixture message 35|header:false|frame:true")
      }, object: nil)
      let acceptance = XCTWaiter.wait(for: [accepted], timeout: 10)
      if acceptance != .completed {
        attachSpeech(nativeFocus, name: "Latest-message jump native focus timeout")
      }
      XCTAssertEqual(acceptance, .completed)

      var after = ""
      var speechSamples: [String] = []
      let focused = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
        let previous = after
        do {
          after = try voiceOver.currentSpeech().utterance
        } catch {
          after = "Speech unavailable: \(error)"
        }
        if after != previous {
          speechSamples.append("\(ProcessInfo.processInfo.systemUptime - started)s: \(after)")
        }
        return after.contains("Fixture message 35")
      }, object: nil)
      let result = XCTWaiter.wait(for: [focused], timeout: 10)
      if result != .completed {
        attachSpeech("Button hittable before navigation: \(jump.isHittable)", name: "Latest-message jump speech timeout")
      }
      attachSpeech(speechSamples.joined(separator: "\n"), name: "Latest-message jump speech samples")
      attachSpeech("Before: \(before)\nAfter: \(after)", name: "Latest-message jump accepted focus")
      XCTAssertEqual(result, .completed)
      let backward = try voiceOver.moveBackward().utterance
      attachSpeech(backward, name: "Latest-message jump backward navigation")
      XCTAssertTrue(backward.contains("Fixture message 34"))
      let forward = try voiceOver.moveForward().utterance
      attachSpeech(forward, name: "Latest-message jump forward navigation")
      XCTAssertTrue(forward.contains("Fixture message 35"))
      XCTAssertFalse(jump.isHittable)
    }
  }

  private func withTranscript(
    voiceOverEnabled: Bool = true,
    longDay: Bool = false,
    verifyHeadingFocus: Bool = false,
    verify: (XCUIApplication, XCUIVoiceOverService) throws -> Void
  ) throws {
    let app = XCUIApplication()
    let voiceOver = XCUIDevice.shared.voiceOverService
    let wasEnabled = voiceOver.isEnabled
    defer {
      app.terminate()
      if voiceOver.isEnabled != wasEnabled {
        do {
          if wasEnabled { try voiceOver.enable() } else { try voiceOver.disable() }
        } catch {
          XCTFail("Could not restore VoiceOver: \(error)")
        }
      }
    }
    if voiceOver.isEnabled != voiceOverEnabled {
      if voiceOverEnabled { try voiceOver.enable() } else { try voiceOver.disable() }
    }
    if longDay { app.launchArguments.append("-long-day") }
    if verifyHeadingFocus { app.launchArguments.append("-verify-heading-focus") }
    app.launch()
    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
    XCTAssertEqual(voiceOver.isEnabled, voiceOverEnabled)
    try verify(app, voiceOver)
  }

  private func attachSpeech(_ text: String, name: String) {
    let speech = XCTAttachment(string: text)
    speech.name = name
    speech.lifetime = .keepAlways
    add(speech)
  }
}
