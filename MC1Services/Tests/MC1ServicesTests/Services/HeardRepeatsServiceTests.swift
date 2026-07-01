// MC1Services/Tests/MC1ServicesTests/Services/HeardRepeatsServiceTests.swift
import Foundation
@testable import MC1Services
import Testing

@Suite("HeardRepeatsService Tests")
struct HeardRepeatsServiceTests {
  // MARK: - ChannelMessageFormat.parse Tests

  @Test
  func `parse with valid format returns sender and message`() {
    let result = ChannelMessageFormat.parse("NodeName: Hello world")

    #expect(result != nil)
    #expect(result?.senderName == "NodeName")
    #expect(result?.messageText == "Hello world")
  }

  @Test
  func `parse with no colon returns nil`() {
    let result = ChannelMessageFormat.parse("No colon here")

    #expect(result == nil)
  }

  @Test
  func `parse with colon at start returns nil`() {
    let result = ChannelMessageFormat.parse(": Message without sender")

    #expect(result == nil)
  }

  @Test
  func `parse with empty message returns empty text`() {
    let result = ChannelMessageFormat.parse("Sender:")

    #expect(result != nil)
    #expect(result?.senderName == "Sender")
    #expect(result?.messageText == "")
  }

  @Test
  func `parse with message containing colons only splits on first`() {
    let result = ChannelMessageFormat.parse("Sender: Time is 10:30:00")

    #expect(result != nil)
    #expect(result?.senderName == "Sender")
    #expect(result?.messageText == "Time is 10:30:00")
  }

  @Test
  func `parse trims whitespace from message`() {
    let result = ChannelMessageFormat.parse("Node:   Padded message   ")

    #expect(result != nil)
    #expect(result?.messageText == "Padded message")
  }

  @Test
  func `parse preserves spaces in sender name`() {
    let result = ChannelMessageFormat.parse("Node With Spaces: Message")

    #expect(result != nil)
    #expect(result?.senderName == "Node With Spaces")
  }
}
