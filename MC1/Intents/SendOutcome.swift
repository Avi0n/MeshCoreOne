/// The result of attempting the durable-queue send after confirmation. A nil
/// services re-read maps to `.mustForeground` so the caller escalates instead of
/// reporting a queued message that was never enqueued.
enum SendOutcome: Equatable {
  case queued
  case mustForeground
}
