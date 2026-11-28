import MC1Services
import SwiftUI

/// Debug details for one game session: its state, the routes used and every DM sent and received.
struct GameDebugView: View {
  let title: String
  let summary: [(String, String)]
  let log: [GameLogEntry]

  @State private var expanded: Set<UUID> = []

  var body: some View {
    List {
      Section(GameL10n.Debug.session) {
        ForEach(summary, id: \.0) { row in
          LabeledContent(row.0) {
            Text(row.1).textSelection(.enabled).multilineTextAlignment(.trailing)
          }
        }
      }

      Section {
        LabeledContent(GameL10n.Debug.sent, value: GameL10n.Debug.sentValue(sent.count, retries))
        LabeledContent(GameL10n.Debug.received, value: "\(log.count - sent.count)")
        LabeledContent(GameL10n.Debug.reachedRadio, value: radioAckSummary)
        if let trip = averageTrip {
          LabeledContent(GameL10n.Debug.averageTrip, value: GameL10n.Debug.milliseconds(trip))
        }
      } header: {
        Text(GameL10n.Debug.delivery)
      } footer: {
        Text(GameL10n.Debug.deliveryFooter)
      }

      if !routes.isEmpty {
        Section(GameL10n.Debug.routes) {
          ForEach(routes, id: \.self) { route in
            Text(route).font(.footnote.monospaced()).textSelection(.enabled)
          }
        }
      }

      Section {
        if log.isEmpty {
          Text(GameL10n.Debug.noMessages).foregroundStyle(.secondary)
        }
        ForEach(log.reversed()) { entry in
          entryRow(entry)
            .contentShape(Rectangle())
            .onTapGesture {
              if expanded.contains(entry.id) { expanded.remove(entry.id) } else { expanded.insert(entry.id) }
            }
        }
      } header: {
        Text(GameL10n.Debug.messages)
      } footer: {
        Text(GameL10n.Debug.messagesFooter)
      }
    }
    .navigationTitle(GameL10n.Debug.title(title))
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .primaryAction) {
        ShareLink(item: exportText) {
          Image(systemName: "square.and.arrow.up")
        }
      }
    }
  }

  // MARK: Rows

  private func entryRow(_ entry: GameLogEntry) -> some View {
    VStack(alignment: .leading, spacing: 3) {
      HStack(spacing: 6) {
        Image(systemName: entry.direction == .sent ? "arrow.up.right" : "arrow.down.left")
          .foregroundStyle(entry.direction == .sent ? .blue : .green)
        Text(entry.type).font(.subheadline.monospaced().bold())
        if entry.attempt > 1 {
          Text(GameL10n.Status.attempt(entry.attempt)).font(.caption2).padding(.horizontal, 5).background(.orange.opacity(0.2), in: .capsule)
        }
        Spacer()
        Text(entry.date.formatted(date: .omitted, time: .standard)).font(.caption).foregroundStyle(.secondary)
      }
      Text(Self.routeText(entry)).font(.caption).foregroundStyle(.secondary)
      if entry.direction == .sent {
        ackLine(entry).font(.caption)
      }
      if let error = entry.error {
        Text(GameL10n.Debug.error(error)).font(.caption).foregroundStyle(.red)
      }
      Text(entry.text)
        .font(.caption2.monospaced())
        .foregroundStyle(.secondary)
        .lineLimit(expanded.contains(entry.id) ? nil : 1)
        .textSelection(.enabled)
    }
    .padding(.vertical, 2)
  }

  @ViewBuilder
  private func ackLine(_ entry: GameLogEntry) -> some View {
    switch entry.radioAck {
    case .delivered?:
      Label(GameL10n.Debug.reachedRadio + (entry.tripTimeMs.map { " · " + GameL10n.Debug.milliseconds($0) } ?? ""), systemImage: "checkmark.circle.fill")
        .foregroundStyle(.green)
    case .waiting?:
      Label(GameL10n.Debug.waitingForRadio, systemImage: "clock").foregroundStyle(.blue)
    case .missing?:
      Label(GameL10n.Debug.noRadioAck, systemImage: "xmark.circle").foregroundStyle(.orange)
    case nil:
      Label(GameL10n.Debug.notSentToRadio, systemImage: "questionmark.circle").foregroundStyle(.secondary)
    }
  }

  // MARK: Derived

  private var sent: [GameLogEntry] {
    log.filter { $0.direction == .sent }
  }

  private var retries: Int {
    sent.filter { $0.attempt > 1 }.count
  }

  private var radioAckSummary: String {
    let tracked = sent.filter { $0.radioAck != nil && $0.radioAck != .waiting }
    guard !tracked.isEmpty else { return "–" }
    let delivered = tracked.filter { $0.radioAck == .delivered }.count
    return GameL10n.Debug.reachedRadioValue(delivered, tracked.count)
  }

  private var averageTrip: Int? {
    let trips = sent.compactMap(\.tripTimeMs).filter { $0 > 0 }
    guard !trips.isEmpty else { return nil }
    return trips.reduce(0, +) / trips.count
  }

  /// Distinct routes, newest first.
  private var routes: [String] {
    var seen: [String] = []
    for entry in log.reversed() {
      let route = (entry.direction == .sent ? "→ " : "← ") + Self.routeText(entry)
      if entry.flood != nil, !seen.contains(route) { seen.append(route) }
    }
    return seen
  }

  static func yesNo(_ value: Bool) -> String {
    value ? GameL10n.Debug.Value.yes : GameL10n.Debug.Value.no
  }

  /// Route in mesh terms (flood, direct, hops, SNR). Kept in English like the exported log it is part of.
  static func routeText(_ entry: GameLogEntry) -> String {
    guard let flood = entry.flood else { return "route unknown" }
    var parts: [String] = []
    if flood {
      parts.append(entry.hopCount.map { "flood · \($0) hop\($0 == 1 ? "" : "s")" } ?? "flood")
    } else if entry.hops.isEmpty {
      parts.append(entry.hopCount == 0 ? "direct · no repeater" : "direct")
    } else {
      let estimated = entry.direction == .received ? " (est.)" : ""
      parts.append("direct\(estimated) · " + entry.hops.map(\.label).joined(separator: " → "))
    }
    if let snr = entry.snr {
      parts.append(String(format: "SNR %.1f dB", snr))
    }
    return parts.joined(separator: " · ")
  }

  static func hex(_ data: Data) -> String {
    data.map { String(format: "%02X", $0) }.joined()
  }

  private var exportText: String {
    var lines = ["\(title) — connection details"]
    lines += summary.map { "\($0.0): \($0.1)" }
    lines.append("")
    for entry in log {
      var line = "\(entry.date.formatted(.iso8601)) \(entry.direction == .sent ? "OUT" : "IN ") \(entry.type)"
      if entry.attempt > 1 { line += " try\(entry.attempt)" }
      line += " [\(Self.routeText(entry))]"
      if let ack = entry.radioAck { line += " radio:\(ack.rawValue)" + (entry.tripTimeMs.map { " \($0)ms" } ?? "") }
      if let error = entry.error { line += " error:\(error)" }
      line += " \(entry.text)"
      lines.append(line)
    }
    return lines.joined(separator: "\n")
  }
}
