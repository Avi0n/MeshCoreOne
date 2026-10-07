import MC1Services

extension RepeaterSettingsViewModel {
  /// CLI argument when default scope is unset (`region default <null>`).
  private static let firmwareNullToken = "<null>"
  /// `RegionMap.exportTo(reply, 160)` yields at most 159 UTF-8 bytes plus a trailing NUL.
  static let firmwareRegionDumpMaxPayloadBytes = 159
  /// Substring on a `region default` reply. Set replies use `defaultScopeSetReplyMarker`, which also matches this.
  private static let defaultScopeReplyMarker = "default scope is"
  private static let defaultScopeSetReplyMarker = "default scope is now"

  func fetchRegions() async {
    isLoadingRegions = true
    regionsError = false
    defer { isLoadingRegions = false }

    do {
      let treeResponse = try await helper.sendAndWait("region", timeout: .seconds(10), rawMatching: true)
      let parsed = Self.parseRegionTree(treeResponse)
      guard !parsed.isEmpty else {
        originalRegions = nil
        regionsError = true
        return
      }
      regions = parsed
      originalRegions = parsed
    } catch {
      if case RemoteNodeError.timeout = error {
        regionsError = true
      }
      logger.warning("Failed to fetch regions: \(error)")
    }
  }

  func fetchDefaultScope() async {
    guard supportsRegionDefaultScope, !isLoadingDefaultScope else { return }
    isLoadingDefaultScope = true
    defer { isLoadingDefaultScope = false }

    do {
      let defaultReply = try await helper.sendAndWait(
        "region default",
        timeout: .seconds(10),
        rawMatching: true
      )
      if let parsed = Self.parseDefaultScopeReply(defaultReply) {
        applyParsedDefaultScope(parsed)
      } else {
        logger.warning("Unparsed region default reply: \(defaultReply)")
      }
    } catch {
      logger.warning("Failed to fetch default scope: \(error)")
    }
  }

  static func parseRegionTree(_ response: String) -> [RepeaterRegionEntry] {
    // Firmware ends each region with LF; a truncated dump's last Unicode scalar is not LF.
    // CRLF is one Swift Character, so the terminator is unicodeScalars.last, not a Character suffix.
    guard response.utf8.count < firmwareRegionDumpMaxPayloadBytes else { return [] }
    guard response.unicodeScalars.last == "\n" else { return [] }

    var entries: [RepeaterRegionEntry] = []
    var stack: [String] = []
    let lines = response.split(
      omittingEmptySubsequences: true,
      whereSeparator: { $0 == "\n" || $0 == "\r\n" }
    )

    for line in lines {
      let depth = line.prefix(while: { $0 == " " }).count
      var text = String(line.dropFirst(depth))
      guard !text.isEmpty else { return [] }

      let floodAllowed: Bool
      if text.hasSuffix(" F") {
        floodAllowed = true
        text = String(text.dropLast(2))
      } else {
        floodAllowed = false
      }

      let isHome: Bool
      if text.hasSuffix("^") {
        isHome = true
        text = String(text.dropLast(1))
      } else {
        isHome = false
      }

      guard !text.isEmpty, !text.contains(" ") else { return [] }

      guard depth <= stack.count else { return [] }
      stack.removeSubrange(depth...)
      stack.append(text)
      let parentName: String? = depth == 0 ? nil : stack[depth - 1]

      entries.append(RepeaterRegionEntry(
        name: text,
        parentName: parentName,
        depth: depth,
        floodAllowed: floodAllowed,
        isHome: isHome
      ))
    }

    return entries
  }

  /// Unset (`<null>` or `*`) versus a named region.
  enum ParsedDefaultScope: Equatable {
    case cleared
    case named(String)
  }

  /// Nil is an unparsed reply, not an unset scope.
  static func parseDefaultScopeReply(_ response: String) -> ParsedDefaultScope? {
    let lines = response.split(separator: "\n", omittingEmptySubsequences: true)
    guard let last = lines.last else { return nil }
    var line = last.trimmingCharacters(in: .whitespaces)
    if line.hasPrefix(">") {
      line = String(line.dropFirst()).trimmingCharacters(in: .whitespaces)
    }
    guard line.localizedCaseInsensitiveContains(defaultScopeReplyMarker) else { return nil }

    let tokens = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
    guard let lastToken = tokens.last else { return nil }
    // Firmware treats a default of `*` as unset, same as `<null>`.
    if lastToken == firmwareNullToken || lastToken == RepeaterRegionEntry.unscopedName { return .cleared }
    return .named(lastToken)
  }

  private func applyParsedDefaultScope(_ parsed: ParsedDefaultScope) {
    switch parsed {
    case .cleared:
      defaultScopeName = nil
    case let .named(name):
      defaultScopeName = name
    }
    defaultScopeLoaded = true
  }

  func toggleRegionFlood(name: String) async {
    guard regionsLoaded, !helper.isApplying else { return }
    guard let index = regions.firstIndex(where: { $0.name == name }) else { return }
    let currentlyAllowed = regions[index].floodAllowed
    let command = currentlyAllowed ? "region denyf \(name)" : "region allowf \(name)"

    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait(command)
      if case .ok = CLIResponse.parse(response) {
        if let liveIndex = regions.firstIndex(where: { $0.name == name }) {
          regions[liveIndex].floodAllowed = !currentlyAllowed
          hasUnsavedRegionChanges = true
        }
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  func setDefaultScope(name: String?) async {
    guard supportsRegionDefaultScope, regionsLoaded, !helper.isApplying else { return }
    if name == RepeaterRegionEntry.unscopedName { return }
    if name == defaultScopeName { return }

    let argument = name ?? Self.firmwareNullToken
    let command = "region default \(argument)"

    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait(command, rawMatching: true)
      if response.contains(Self.defaultScopeSetReplyMarker) {
        defaultScopeName = name
        defaultScopeLoaded = true
        if let name, let index = regions.firstIndex(where: { $0.name == name }) {
          regions[index].floodAllowed = true
        }
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  enum AddRegionError: Error, Equatable {
    case rejected
  }

  func addRegion(name: String, parent: RepeaterRegionEntry.Parent) async throws {
    guard regionsLoaded, !helper.isApplying else { throw AddRegionError.rejected }

    let trimmed = name.trimmingCharacters(in: .whitespaces)
    if let validationError = RegionNameValidator.validate(trimmed, existingRegions: regions.map(\.name)) {
      switch validationError {
      case .empty:
        throw AddRegionError.rejected
      case .invalidCharacters, .tooLong, .duplicate:
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
        throw AddRegionError.rejected
      }
    }

    let command: String
    let parentName: String

    switch parent {
    case .unscoped:
      command = "region put \(trimmed)"
      parentName = RepeaterRegionEntry.unscopedName
    case let .named(namedParent):
      guard regions.contains(where: { $0.name == namedParent }) else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
        throw AddRegionError.rejected
      }
      command = "region put \(trimmed) \(namedParent)"
      parentName = namedParent
    }

    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait(command)
      if case .ok = CLIResponse.parse(response) {
        let liveParentIndex = regions.firstIndex(where: { $0.name == parentName })
        let depth = liveParentIndex.map { regions[$0].depth + 1 } ?? 1
        let newEntry = RepeaterRegionEntry(
          name: trimmed,
          parentName: parentName,
          depth: depth,
          floodAllowed: true,
          isHome: false
        )
        if let liveParentIndex {
          let parentDepth = regions[liveParentIndex].depth
          var insertIndex = liveParentIndex + 1
          while insertIndex < regions.count, regions[insertIndex].depth > parentDepth {
            insertIndex += 1
          }
          regions.insert(newEntry, at: insertIndex)
        } else {
          regions.append(newEntry)
        }
        hasUnsavedRegionChanges = true
        helper.isApplying = false
        return
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.addFailed
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
      helper.isApplying = false
      throw error
    }

    helper.isApplying = false
    throw AddRegionError.rejected
  }

  func removeRegion(name: String) async {
    guard regionsLoaded, !helper.isApplying else { return }
    helper.isApplying = true
    helper.errorMessage = nil
    let wasDefault = defaultScopeName == name

    do {
      let response = try await helper.sendAndWait("region remove \(name)")
      if case .ok = CLIResponse.parse(response) {
        regions.removeAll { $0.name == name }
        hasUnsavedRegionChanges = true
        if wasDefault, supportsRegionDefaultScope {
          let clearReply = try await helper.sendAndWait(
            "region default \(Self.firmwareNullToken)",
            rawMatching: true
          )
          if clearReply.contains(Self.defaultScopeSetReplyMarker) {
            defaultScopeName = nil
          } else {
            helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.unknownRegion
          }
        }
      } else if response.contains("not empty") {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.notEmpty
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.removeFailed
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }

  func saveRegions() async {
    guard !helper.isApplying else { return }
    helper.isApplying = true
    helper.errorMessage = nil

    do {
      let response = try await helper.sendAndWait("region save")
      if case .ok = CLIResponse.parse(response) {
        hasUnsavedRegionChanges = false
        await helper.flashSuccess(
          setApplying: { helper.isApplying = $0 },
          setSuccess: { regionsSaveSuccess = $0 }
        )
        return
      } else {
        helper.errorMessage = L10n.RemoteNodes.RemoteNodes.Settings.Regions.saveFailed
      }
    } catch {
      helper.errorMessage = error.userFacingMessage
    }

    helper.isApplying = false
  }
}
