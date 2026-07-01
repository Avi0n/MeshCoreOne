import Foundation
@testable import MC1
import Testing

@Suite("DevicePreferenceStore Tests")
struct DevicePreferenceStoreTests {
  @Test
  func `Auto-update location defaults to false`() throws {
    let suite = "DevicePreferenceStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }

    let store = DevicePreferenceStore(userDefaults: defaults)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: UUID()) == false)
  }

  @Test
  func `GPS source defaults to phone`() throws {
    let suite = "DevicePreferenceStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }

    let store = DevicePreferenceStore(userDefaults: defaults)
    #expect(store.gpsSource(deviceID: UUID()) == .phone)
  }

  @Test
  func `Auto-update values are scoped per device`() throws {
    let suite = "DevicePreferenceStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }

    let store = DevicePreferenceStore(userDefaults: defaults)
    let deviceA = UUID()
    let deviceB = UUID()

    store.setAutoUpdateLocationEnabled(true, deviceID: deviceA)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceA) == true)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceB) == false)

    store.setAutoUpdateLocationEnabled(true, deviceID: deviceB)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceA) == true)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceB) == true)

    store.setAutoUpdateLocationEnabled(false, deviceID: deviceA)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceA) == false)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceB) == true)
  }

  @Test
  func `GPS source values are scoped per device`() throws {
    let suite = "DevicePreferenceStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }

    let store = DevicePreferenceStore(userDefaults: defaults)
    let deviceA = UUID()
    let deviceB = UUID()

    store.setGPSSource(.device, deviceID: deviceA)
    #expect(store.gpsSource(deviceID: deviceA) == .device)
    #expect(store.gpsSource(deviceID: deviceB) == .phone)

    store.setGPSSource(.device, deviceID: deviceB)
    #expect(store.gpsSource(deviceID: deviceA) == .device)
    #expect(store.gpsSource(deviceID: deviceB) == .device)

    store.setGPSSource(.phone, deviceID: deviceA)
    #expect(store.gpsSource(deviceID: deviceA) == .phone)
    #expect(store.gpsSource(deviceID: deviceB) == .device)
  }

  @Test
  func `hasSetGPSSource returns false when no source has been set`() throws {
    let suite = "DevicePreferenceStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }

    let store = DevicePreferenceStore(userDefaults: defaults)
    #expect(store.hasSetGPSSource(deviceID: UUID()) == false)
  }

  @Test
  func `hasSetGPSSource returns true after setting a source`() throws {
    let suite = "DevicePreferenceStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }

    let store = DevicePreferenceStore(userDefaults: defaults)
    let deviceID = UUID()

    #expect(store.hasSetGPSSource(deviceID: deviceID) == false)
    store.setGPSSource(.phone, deviceID: deviceID)
    #expect(store.hasSetGPSSource(deviceID: deviceID) == true)
  }

  @Test
  func `Setting and getting round-trips correctly`() throws {
    let suite = "DevicePreferenceStoreTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }

    let store = DevicePreferenceStore(userDefaults: defaults)
    let deviceID = UUID()

    store.setAutoUpdateLocationEnabled(true, deviceID: deviceID)
    store.setGPSSource(.device, deviceID: deviceID)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceID) == true)
    #expect(store.gpsSource(deviceID: deviceID) == .device)

    store.setAutoUpdateLocationEnabled(false, deviceID: deviceID)
    store.setGPSSource(.phone, deviceID: deviceID)
    #expect(store.isAutoUpdateLocationEnabled(deviceID: deviceID) == false)
    #expect(store.gpsSource(deviceID: deviceID) == .phone)
  }
}
