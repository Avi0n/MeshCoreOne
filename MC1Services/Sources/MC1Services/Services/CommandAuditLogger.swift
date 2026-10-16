import Foundation

/// Dedicated logger for command audit trails.
/// Logs all repeater and room commands with consistent formatting.
actor CommandAuditLogger {
  // MARK: - Types

  /// Direction of the command
  enum Direction: String {
    case out = "->"
    case `in` = "<-"
  }

  /// Target type (repeater or room)
  enum Target: String {
    case repeater = "REPEATER"
    case room = "ROOM"
  }

  // MARK: - Properties

  private let logger = PersistentLogger(subsystem: "com.mc1", category: "CommandAudit")
  private let prefix = "[CMD]"

  // MARK: - Initialization

  init() {}

  // MARK: - Login/Logout

  /// Log a login request being sent
  func logLoginRequest(target: Target, publicKey: Data, pathLength: UInt8) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.out.rawValue) \(target.rawValue) LOGIN to=\(keyHex) pathLen=\(pathLength)")
  }

  /// Log a successful login response
  func logLoginSuccess(target: Target, publicKey: Data, isAdmin: Bool) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.in.rawValue) \(target.rawValue) LOGIN_OK from=\(keyHex) admin=\(isAdmin)")
  }

  /// Log a failed login response
  func logLoginFailed(target: Target, publicKey: Data, reason: String) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.warning("\(prefix) \(Direction.in.rawValue) \(target.rawValue) LOGIN_FAIL from=\(keyHex) reason=\(reason)")
  }

  /// Log a logout request being sent
  func logLogout(target: Target, publicKey: Data) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.out.rawValue) \(target.rawValue) LOGOUT to=\(keyHex)")
  }

  // MARK: - Status/Telemetry

  /// Log a status request being sent
  func logStatusRequest(target: Target, publicKey: Data) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.out.rawValue) \(target.rawValue) STATUS_REQ to=\(keyHex)")
  }

  /// Log a status response received
  func logStatusResponse(target: Target, publicKey: Data, batteryMv: UInt16?, uptimeSec: UInt32?) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    let battery = batteryMv.map { "\($0)mV" } ?? "n/a"
    let uptime = uptimeSec.map { "\($0)s" } ?? "n/a"
    logger.info("\(prefix) \(Direction.in.rawValue) \(target.rawValue) STATUS from=\(keyHex) battery=\(battery) uptime=\(uptime)")
  }

  /// Log a telemetry request being sent
  func logTelemetryRequest(target: Target, publicKey: Data) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.out.rawValue) \(target.rawValue) TELEM_REQ to=\(keyHex)")
  }

  /// Log a telemetry response received
  func logTelemetryResponse(target: Target, publicKey: Data, pointCount: Int) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.in.rawValue) \(target.rawValue) TELEM from=\(keyHex) points=\(pointCount)")
  }

  // MARK: - CLI Commands (Repeater)

  /// Log a CLI command being sent (with password redaction)
  func logCLICommand(publicKey: Data, command: String) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    let redactedCmd = LogRedaction.cliCommand(command)
    logger.info("\(prefix) \(Direction.out.rawValue) REPEATER CLI to=\(keyHex) cmd=\"\(redactedCmd)\"")
  }

  /// Log a CLI response received (full content logged)
  func logCLIResponse(publicKey: Data, response: String) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    // Truncate very long responses for readability
    let truncated = response.count <= 100 ? response : String(response.prefix(100)) + "..."
    logger.info("\(prefix) \(Direction.in.rawValue) REPEATER CLI_RESP from=\(keyHex) resp=\"\(truncated)\"")
  }

  // MARK: - Neighbors (Repeater)

  /// Log a neighbors request being sent
  func logNeighborsRequest(publicKey: Data, count: UInt8, offset: UInt16) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.out.rawValue) REPEATER NEIGHBORS_REQ to=\(keyHex) count=\(count) offset=\(offset)")
  }

  /// Log a neighbors response received
  func logNeighborsResponse(publicKey: Data, totalCount: Int, returnedCount: Int) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.in.rawValue) REPEATER NEIGHBORS from=\(keyHex) total=\(totalCount) returned=\(returnedCount)")
  }

  // MARK: - Room Messages (metadata only)

  /// Log a room message being posted (no content, only length)
  func logRoomMessagePosted(publicKey: Data, messageLength: Int) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.out.rawValue) ROOM MSG to=\(keyHex) len=\(messageLength)")
  }

  /// Log a room message received (no content, only metadata)
  func logRoomMessageReceived(roomPublicKey: Data, authorPrefix: Data, messageLength: Int) {
    let roomHex = LogRedaction.publicKeyHex(roomPublicKey)
    let authorHex = authorPrefix.map { String(format: "%02x", $0) }.joined()
    logger.info("\(prefix) \(Direction.in.rawValue) ROOM MSG from=\(roomHex) author=\(authorHex) len=\(messageLength)")
  }

  // MARK: - Keep-alive

  /// Log a keep-alive request being sent
  func logKeepAlive(target: Target, publicKey: Data) {
    let keyHex = LogRedaction.publicKeyHex(publicKey)
    logger.info("\(prefix) \(Direction.out.rawValue) \(target.rawValue) KEEPALIVE to=\(keyHex)")
  }
}
