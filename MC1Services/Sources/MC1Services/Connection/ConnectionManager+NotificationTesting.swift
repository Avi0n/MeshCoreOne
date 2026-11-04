extension ConnectionManager {
  func notificationServiceForTesting() -> NotificationService? {
    #if DEBUG
      if let testNotificationSystemOperations {
        return NotificationService(notificationDefaults: .standard, systemOperations: testNotificationSystemOperations)
      }
    #endif
    return nil
  }
}
