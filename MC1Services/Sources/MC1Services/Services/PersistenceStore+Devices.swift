import Foundation
import SwiftData

extension PersistenceStore {

    // MARK: - Device Operations

    /// Fetch all devices
    public func fetchDevices() throws -> [DeviceDTO] {
        let descriptor = FetchDescriptor<Device>(
            sortBy: [SortDescriptor(\Device.lastConnected, order: .reverse)]
        )
        let devices = try modelContext.fetch(descriptor)
        return devices.map { DeviceDTO(from: $0) }
    }

    /// Fetch a device by ID
    public func fetchDevice(id: UUID) throws -> DeviceDTO? {
        let targetID = id
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { DeviceDTO(from: $0) }
    }

    /// Fetch the active device
    public func fetchActiveDevice() throws -> DeviceDTO? {
        let predicate = #Predicate<Device> { device in
            device.isActive == true
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { DeviceDTO(from: $0) }
    }

    /// Save or update a device
    public func saveDevice(_ dto: DeviceDTO) throws {
        let targetID = dto.id
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let existing = try modelContext.fetch(descriptor).first {
            existing.apply(dto)
        } else {
            // Create new
            let device = Device(
                id: dto.id,
                publicKey: dto.publicKey,
                nodeName: dto.nodeName,
                firmwareVersion: dto.firmwareVersion,
                firmwareVersionString: dto.firmwareVersionString,
                manufacturerName: dto.manufacturerName,
                buildDate: dto.buildDate,
                maxContacts: dto.maxContacts,
                maxChannels: dto.maxChannels,
                frequency: dto.frequency,
                bandwidth: dto.bandwidth,
                spreadingFactor: dto.spreadingFactor,
                codingRate: dto.codingRate,
                txPower: dto.txPower,
                maxTxPower: dto.maxTxPower,
                latitude: dto.latitude,
                longitude: dto.longitude,
                blePin: dto.blePin,
                clientRepeat: dto.clientRepeat,
                pathHashMode: dto.pathHashMode,
                preRepeatFrequency: dto.preRepeatFrequency,
                preRepeatBandwidth: dto.preRepeatBandwidth,
                preRepeatSpreadingFactor: dto.preRepeatSpreadingFactor,
                preRepeatCodingRate: dto.preRepeatCodingRate,
                manualAddContacts: dto.manualAddContacts,
                autoAddConfig: dto.autoAddConfig,
                autoAddMaxHops: dto.autoAddMaxHops,
                multiAcks: dto.multiAcks,
                telemetryModeBase: dto.telemetryModeBase,
                telemetryModeLoc: dto.telemetryModeLoc,
                telemetryModeEnv: dto.telemetryModeEnv,
                advertLocationPolicy: dto.advertLocationPolicy,
                lastConnected: dto.lastConnected,
                lastContactSync: dto.lastContactSync,
                isActive: dto.isActive,
                ocvPreset: dto.ocvPreset,
                customOCVArrayString: dto.customOCVArrayString,
                connectionMethods: dto.connectionMethods,
                knownRegions: dto.knownRegions
            )
            modelContext.insert(device)
        }

        try modelContext.save()
    }

    /// Set a device as active (deactivates others)
    public func setActiveDevice(id: UUID) throws {
        // Deactivate all devices first
        let allDevices = try modelContext.fetch(FetchDescriptor<Device>())
        for device in allDevices {
            device.isActive = false
        }

        // Activate the specified device
        let targetID = id
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let device = try modelContext.fetch(descriptor).first {
            device.isActive = true
            device.lastConnected = Date()
        }

        try modelContext.save()
    }

    /// Update the lastContactSync timestamp for a device.
    /// Used to track incremental sync progress.
    public func updateDeviceLastContactSync(deviceID: UUID, timestamp: UInt32) throws {
        let targetID = deviceID
        let predicate = #Predicate<Device> { device in
            device.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let device = try modelContext.fetch(descriptor).first else {
            throw PersistenceStoreError.deviceNotFound
        }
        device.lastContactSync = timestamp
        try modelContext.save()
    }

    /// Adds a known region to a device if not already present
    public func addDeviceKnownRegion(deviceID: UUID, region: String) throws {
        let targetDeviceID = deviceID
        let devicePredicate = #Predicate<Device> { $0.id == targetDeviceID }
        var deviceDescriptor = FetchDescriptor<Device>(predicate: devicePredicate)
        deviceDescriptor.fetchLimit = 1

        guard let device = try modelContext.fetch(deviceDescriptor).first else {
            throw PersistenceStoreError.deviceNotFound
        }

        guard !device.knownRegions.contains(region) else { return }
        device.knownRegions.append(region)
        try modelContext.save()
    }

    /// Removes a known region from a device and clears regionScope on affected channels
    public func removeDeviceKnownRegion(deviceID: UUID, region: String) throws {
        let targetDeviceID = deviceID
        let devicePredicate = #Predicate<Device> { $0.id == targetDeviceID }
        var deviceDescriptor = FetchDescriptor<Device>(predicate: devicePredicate)
        deviceDescriptor.fetchLimit = 1

        guard let device = try modelContext.fetch(deviceDescriptor).first else {
            throw PersistenceStoreError.deviceNotFound
        }

        device.knownRegions.removeAll { $0 == region }

        let channelPredicate = #Predicate<Channel> { $0.deviceID == targetDeviceID }
        let channels = try modelContext.fetch(FetchDescriptor<Channel>(predicate: channelPredicate))
        for channel in channels where channel.regionScope == region {
            channel.regionScope = nil
        }

        try modelContext.save()
    }

    /// Delete a device and all its associated data
    public func deleteDevice(id: UUID) throws {
        let targetID = id

        // Delete associated contacts
        let contactPredicate = #Predicate<Contact> { contact in
            contact.deviceID == targetID
        }
        let contacts = try modelContext.fetch(FetchDescriptor(predicate: contactPredicate))
        for contact in contacts {
            modelContext.delete(contact)
        }

        // Delete associated messages
        let messagePredicate = #Predicate<Message> { message in
            message.deviceID == targetID
        }
        let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))
        for message in messages {
            modelContext.delete(message)
        }

        // Delete associated channels
        let channelPredicate = #Predicate<Channel> { channel in
            channel.deviceID == targetID
        }
        let channels = try modelContext.fetch(FetchDescriptor(predicate: channelPredicate))
        for channel in channels {
            modelContext.delete(channel)
        }

        // Delete associated saved trace paths (runs cascade automatically)
        let pathPredicate = #Predicate<SavedTracePath> { path in
            path.deviceID == targetID
        }
        let paths = try modelContext.fetch(FetchDescriptor(predicate: pathPredicate))
        for path in paths {
            modelContext.delete(path)
        }

        // Delete the device
        let devicePredicate = #Predicate<Device> { device in
            device.id == targetID
        }
        if let device = try modelContext.fetch(FetchDescriptor(predicate: devicePredicate)).first {
            modelContext.delete(device)
        }

        try modelContext.save()
    }
}
