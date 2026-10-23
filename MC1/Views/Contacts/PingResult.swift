enum PingResult {
  case success(latencyMs: Int, snrThere: Double, snrBack: Double)
  case error(String)
}
