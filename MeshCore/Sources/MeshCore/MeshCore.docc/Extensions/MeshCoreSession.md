# ``MeshCore/MeshCoreSession``

@Metadata {
    @DocumentationExtension(mergeBehavior: append)
}

## Topics

### Connection

- ``start()``
- ``stop()``
- ``connectionState``
- ``currentSelfInfo``

### Events

- ``events()``
- ``waitForEvent(matching:timeout:)``
- ``waitForEvent(filter:timeout:)``

### Contacts

- ``getContacts(since:)``
- ``ensureContacts(force:)``
- ``cachedContacts``
- ``cachedPendingContacts``
- ``getContactByName(_:exactMatch:)``
- ``getContactByKeyPrefix(_:)-swift.method``
- ``addContact(_:)``
- ``removeContact(publicKey:)``
- ``updateContact(publicKey:type:flags:outPathLength:outPath:advertisedName:lastAdvertisement:latitude:longitude:)``
- ``resetPath(publicKey:)``
- ``shareContact(publicKey:)``
- ``changeContactPath(_:path:)``
- ``changeContactFlags(_:flags:)``
- ``exportContact(publicKey:)``
- ``importContact(cardData:)``
- ``setAutoUpdateContacts(_:)``

### Messaging

- ``sendMessage(to:text:timestamp:)-swift.method``
- ``sendMessageWithRetry(to:text:timestamp:maxAttempts:floodAfter:maxFloodAttempts:timeout:)``
- ``sendChannelMessage(channel:text:timestamp:)``
- ``sendChannelData(channelIndex:dataType:payload:pathLength:pathBytes:)``
- ``sendCommand(to:command:timestamp:)``
- ``getMessage()``
- ``startAutoMessageFetching()``
- ``stopAutoMessageFetching()``

### Device Configuration

- ``setName(_:)``
- ``setCoordinates(latitude:longitude:)``
- ``setTxPower(_:)``
- ``setRadio(frequency:bandwidth:spreadingFactor:codingRate:)``
- ``setTuning(rxDelay:af:)``
- ``setTime(_:)``
- ``getTime()``
- ``setChannel(index:name:secret:)-swift.method``
- ``getChannel(index:)``
- ``setFloodScope(_:)``
- ``setDefaultFloodScope(name:scopeKey:)``
- ``setDefaultFloodScope(name:scope:)``
- ``getDefaultFloodScope()``
- ``setOtherParams(_:)``
- ``setDevicePin(_:)``
- ``setCustomVar(key:value:)``
- ``getCustomVars()``

### Telemetry

- ``getSelfTelemetry()``
- ``requestTelemetry(from:)-swift.method``
- ``requestStatus(from:)-swift.method``
- ``requestMMA(from:start:end:)``
- ``requestNeighbours(from:count:offset:orderBy:pubkeyPrefixLength:)``
- ``fetchAllNeighbours(from:orderBy:pubkeyPrefixLength:)``
- ``requestACL(from:)``

### Statistics

- ``getStatsCore()``
- ``getStatsRadio()``
- ``getStatsPackets()``
- ``getBattery()``
- ``queryDevice()``

### Authentication

- ``sendLogin(to:password:)-swift.method``
- ``sendLogout(to:)``

### Signing

- ``sign(_:chunkSize:timeout:)``
- ``signStart()``
- ``signData(_:)``
- ``signFinish(timeout:)``

### Advanced

- ``sendAdvertisement(flood:)``
- ``sendPathDiscovery(to:)``
- ``sendTrace(tag:authCode:flags:path:)``
- ``sendControlData(type:payload:)``
- ``sendNodeDiscoverRequest(filter:prefixOnly:tag:since:)``
- ``exportPrivateKey()``
- ``importPrivateKey(_:)``
- ``factoryReset()``
- ``reboot()``
