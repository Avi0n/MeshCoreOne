@testable import MC1Services
import Testing

struct CLIResponseTests {
  // MARK: - Prompt Prefix Stripping

  @Test func `parse prompt prefix strips prefix`() {
    let result = CLIResponse.parse("> OK")
    #expect(result == .ok)
  }

  @Test func `parse no prompt prefix still works`() {
    let result = CLIResponse.parse("OK")
    #expect(result == .ok)
  }

  @Test func `parse prompt prefix with whitespace`() {
    let result = CLIResponse.parse("  > OK  ")
    #expect(result == .ok)
  }

  @Test func `parse ok with message clock set`() {
    let result = CLIResponse.parse("OK - clock set: 16:13 - 9/1/2026 UTC")
    #expect(result == .ok)
  }

  @Test func `parse ok with message with prompt prefix`() {
    let result = CLIResponse.parse("> OK - clock set: 16:13 - 9/1/2026 UTC")
    #expect(result == .ok)
  }

  // MARK: - Radio with Prompt

  @Test func `parse radio with prompt prefix`() {
    let result = CLIResponse.parse("> 910.5250244,62.5,7,8", forQuery: "get radio")
    if case let .radio(freq, bw, sf, cr) = result {
      #expect(abs(freq - 910.5250244) < 0.0001)
      #expect(abs(bw - 62.5) < 0.01)
      #expect(sf == 7)
      #expect(cr == 8)
    } else {
      Issue.record("Expected .radio, got \(result)")
    }
  }

  @Test func `parse radio without prompt prefix`() {
    let result = CLIResponse.parse("915.000,250.0,10,5", forQuery: "get radio")
    if case let .radio(freq, bw, sf, cr) = result {
      #expect(abs(freq - 915.0) < 0.0001)
      #expect(abs(bw - 250.0) < 0.01)
      #expect(sf == 10)
      #expect(cr == 5)
    } else {
      Issue.record("Expected .radio, got \(result)")
    }
  }

  // MARK: - Name with Prompt

  @Test func `parse name with prompt prefix`() {
    let result = CLIResponse.parse("> Sunnyslope Repeater", forQuery: "get name")
    #expect(result == .name("Sunnyslope Repeater"))
  }

  @Test func `parse name without prompt prefix`() {
    let result = CLIResponse.parse("My Node", forQuery: "get name")
    #expect(result == .name("My Node"))
  }

  // MARK: - Repeat Mode with Prompt

  @Test func `parse repeat mode on with prompt prefix`() {
    let result = CLIResponse.parse("> on", forQuery: "get repeat")
    #expect(result == .repeatMode(true))
  }

  @Test func `parse repeat mode off with prompt prefix`() {
    let result = CLIResponse.parse("> off", forQuery: "get repeat")
    #expect(result == .repeatMode(false))
  }

  @Test func `parse repeat mode without prompt prefix`() {
    let result = CLIResponse.parse("on", forQuery: "get repeat")
    #expect(result == .repeatMode(true))
  }

  // MARK: - TX Power with Prompt

  @Test func `parse tx power with prompt prefix`() {
    let result = CLIResponse.parse("> 22", forQuery: "get tx")
    #expect(result == .txPower(22))
  }

  @Test func `parse tx power without prompt prefix`() {
    let result = CLIResponse.parse("17", forQuery: "get tx")
    #expect(result == .txPower(17))
  }

  // MARK: - Coordinates with Prompt

  @Test func `parse latitude with prompt prefix`() {
    let result = CLIResponse.parse("> 33.4484", forQuery: "get lat")
    if case let .latitude(lat) = result {
      #expect(abs(lat - 33.4484) < 0.0001)
    } else {
      Issue.record("Expected .latitude, got \(result)")
    }
  }

  @Test func `parse latitude without prompt prefix`() {
    let result = CLIResponse.parse("40.7128", forQuery: "get lat")
    if case let .latitude(lat) = result {
      #expect(abs(lat - 40.7128) < 0.0001)
    } else {
      Issue.record("Expected .latitude, got \(result)")
    }
  }

  @Test func `parse longitude with prompt prefix`() {
    let result = CLIResponse.parse("> -112.0740", forQuery: "get lon")
    if case let .longitude(lon) = result {
      #expect(abs(lon - -112.0740) < 0.0001)
    } else {
      Issue.record("Expected .longitude, got \(result)")
    }
  }

  @Test func `parse longitude without prompt prefix`() {
    let result = CLIResponse.parse("-74.0060", forQuery: "get lon")
    if case let .longitude(lon) = result {
      #expect(abs(lon - -74.0060) < 0.0001)
    } else {
      Issue.record("Expected .longitude, got \(result)")
    }
  }

  // MARK: - Intervals with Prompt

  @Test func `parse advert interval with prompt prefix`() {
    let result = CLIResponse.parse("> 5", forQuery: "get advert.interval")
    #expect(result == .advertInterval(5))
  }

  @Test func `parse advert interval without prompt prefix`() {
    let result = CLIResponse.parse("10", forQuery: "get advert.interval")
    #expect(result == .advertInterval(10))
  }

  @Test func `parse flood advert interval with prompt prefix`() {
    let result = CLIResponse.parse("> 12", forQuery: "get flood.advert.interval")
    #expect(result == .floodAdvertInterval(12))
  }

  @Test func `parse flood advert interval without prompt prefix`() {
    let result = CLIResponse.parse("6", forQuery: "get flood.advert.interval")
    #expect(result == .floodAdvertInterval(6))
  }

  @Test func `parse flood max with prompt prefix`() {
    let result = CLIResponse.parse("> 3", forQuery: "get flood.max")
    #expect(result == .floodMax(3))
  }

  @Test func `parse flood max without prompt prefix`() {
    let result = CLIResponse.parse("4", forQuery: "get flood.max")
    #expect(result == .floodMax(4))
  }

  // MARK: - Error with Prompt

  @Test func `parse error with prompt prefix`() {
    let result = CLIResponse.parse("> Error: permission denied")
    if case let .error(msg) = result {
      #expect(msg == "Error: permission denied")
    } else {
      Issue.record("Expected .error, got \(result)")
    }
  }

  @Test func `parse error without prompt prefix`() {
    let result = CLIResponse.parse("Error: invalid value")
    if case let .error(msg) = result {
      #expect(msg == "Error: invalid value")
    } else {
      Issue.record("Expected .error, got \(result)")
    }
  }

  @Test func `parse unknown command with prompt prefix`() {
    let result = CLIResponse.parse("> Error: unknown command")
    #expect(result == .unknownCommand("Error: unknown command"))
  }

  // MARK: - Version with Prompt

  @Test func `parse version with prompt prefix`() {
    let result = CLIResponse.parse("> MeshCore v1.10.0 (2025-04-18)")
    #expect(result == .version("MeshCore v1.10.0 (2025-04-18)"))
  }

  @Test func `parse version without prompt prefix`() {
    let result = CLIResponse.parse("MeshCore v1.11.0 (2025-05-01)")
    #expect(result == .version("MeshCore v1.11.0 (2025-05-01)"))
  }

  @Test func `parse version short format with prompt prefix`() {
    let result = CLIResponse.parse("> v1.12.0 (2025-06-15)")
    #expect(result == .version("v1.12.0 (2025-06-15)"))
  }

  @Test func `parse version non standard format with query hint`() {
    let result = CLIResponse.parse("1.11.0-letsmesh.net-dev-2026-01-06-09005fa (Build: 06-Jan-2026)", forQuery: "ver")
    #expect(result == .version("1.11.0-letsmesh.net-dev-2026-01-06-09005fa (Build: 06-Jan-2026)"))
  }

  // MARK: - Device Time with Prompt

  @Test func `parse device time with prompt prefix`() {
    let result = CLIResponse.parse("> 06:40 - 18/4/2025 UTC")
    #expect(result == .deviceTime("06:40 - 18/4/2025 UTC"))
  }

  @Test func `parse device time without prompt prefix`() {
    let result = CLIResponse.parse("14:30 - 25/12/2025 UTC")
    #expect(result == .deviceTime("14:30 - 25/12/2025 UTC"))
  }

  // MARK: - Owner Info

  @Test func `parse owner info plain text`() {
    let result = CLIResponse.parse("some|pipe|text", forQuery: "get owner.info")
    #expect(result == .ownerInfo("some|pipe|text"))
  }

  @Test func `parse owner info with prompt prefix`() {
    let result = CLIResponse.parse("> 915MHz|KD7ABC|example.com", forQuery: "get owner.info")
    #expect(result == .ownerInfo("915MHz|KD7ABC|example.com"))
  }

  @Test func `parse owner info empty`() {
    let result = CLIResponse.parse("", forQuery: "get owner.info")
    #expect(result == .ownerInfo(""))
  }

  @Test func `parse owner info bare prompt`() {
    // Firmware returns just "> " when owner.info is empty
    let result = CLIResponse.parse("> ", forQuery: "get owner.info")
    #expect(result == .ownerInfo(""))
  }

  @Test func `parse owner info single line`() {
    let result = CLIResponse.parse("just a name", forQuery: "get owner.info")
    #expect(result == .ownerInfo("just a name"))
  }

  @Test func `parse owner info with colon and slash`() {
    // Contact info with ":" and "/" was misclassified as deviceTime
    let result = CLIResponse.parse("> Contact: KD7ABC / 145.230", forQuery: "get owner.info")
    #expect(result == .ownerInfo("Contact: KD7ABC / 145.230"))
  }

  @Test func `parse name with colon and slash`() {
    // Name with ":" and "/" was misclassified as deviceTime
    let result = CLIResponse.parse("> Repeater: East / West", forQuery: "get name")
    #expect(result == .name("Repeater: East / West"))
  }

  @Test func `parse device time still works without query hint`() {
    let result = CLIResponse.parse("06:40 - 18/4/2025 UTC")
    #expect(result == .deviceTime("06:40 - 18/4/2025 UTC"))
  }

  // MARK: - Edge Cases

  @Test func `parse greater than in content not stripped`() {
    // Content that starts with ">" but not "> " should not be stripped
    let result = CLIResponse.parse(">nosuchcommand", forQuery: "get name")
    #expect(result == .name(">nosuchcommand"))
  }

  @Test func `parse multiple greater than only first stripped`() {
    // Only the first "> " should be stripped
    let result = CLIResponse.parse("> > nested prompt", forQuery: "get name")
    #expect(result == .name("> nested prompt"))
  }

  @Test func `parse empty after strip`() {
    // When input is "> ", trimming whitespace gives ">", which is the bare
    // CLI prompt character — treated as empty content
    let result = CLIResponse.parse("> ")
    #expect(result == .raw(""))
  }

  @Test func `parse just prompt`() {
    // Just the prompt character without space — treated as empty content
    let result = CLIResponse.parse(">")
    #expect(result == .raw(""))
  }

  // MARK: - Query Hint Matching (Integration)

  // These tests verify that query hint matching correctly identifies response types
  // even when responses have the prompt prefix. This tests the full flow that
  // RepeaterSettingsViewModel.handleCLIResponse() uses.

  @Test func `query hint matching longitude with prompt prefix`() {
    // Simulate the query hint matching logic from handleCLIResponse
    var trimmedText = "> -120.338211".trimmingCharacters(in: .whitespacesAndNewlines)

    // Strip prompt - this is what we're fixing
    if trimmedText.hasPrefix("> ") {
      trimmedText = String(trimmedText.dropFirst(2))
    }

    // Now the query matching pattern should work
    let isValidDouble = Double(trimmedText) != nil && !trimmedText.contains(",")
    #expect(isValidDouble == true)

    // And parsing should succeed
    let result = CLIResponse.parse("> -120.338211", forQuery: "get lon")
    if case let .longitude(lon) = result {
      #expect(abs(lon - -120.338211) < 0.0001)
    } else {
      Issue.record("Expected .longitude, got \(result)")
    }
  }

  @Test func `query hint matching repeat mode with prompt prefix`() {
    // Simulate the query hint matching logic from handleCLIResponse
    var trimmedText = "> on".trimmingCharacters(in: .whitespacesAndNewlines)

    // Strip prompt - this is what we're fixing
    if trimmedText.hasPrefix("> ") {
      trimmedText = String(trimmedText.dropFirst(2))
    }

    // Now the query matching pattern should work
    let isValidRepeatMode = trimmedText.lowercased() == "on" || trimmedText.lowercased() == "off"
    #expect(isValidRepeatMode == true)

    // And parsing should succeed
    let result = CLIResponse.parse("> on", forQuery: "get repeat")
    #expect(result == .repeatMode(true))
  }
}
