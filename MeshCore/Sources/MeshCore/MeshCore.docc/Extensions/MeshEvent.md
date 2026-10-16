# ``MeshCore/MeshEvent``

@Metadata {
    @DocumentationExtension(mergeBehavior: append)
}

## Topics

### Connection Lifecycle

- ``connectionStateChanged(_:)``

### Command Responses

- ``ok(value:)``
- ``error(code:)``

### Device Information

- ``selfInfo(_:)``
- ``deviceInfo(_:)``
- ``battery(_:)``
- ``currentTime(_:)``
- ``customVars(_:)``
- ``channelInfo(_:)``
- ``defaultFloodScope(_:)``
- ``statsCore(_:)``
- ``statsRadio(_:)``
- ``statsPackets(_:)``

### Contact Management

- ``contactsStart(count:)``
- ``contact(_:)``
- ``contactsEnd(lastModified:)``
- ``newContact(_:)``
- ``contactURI(_:)``

### Messaging

- ``messageSent(_:)``
- ``contactMessageReceived(_:)``
- ``channelMessageReceived(_:)``
- ``channelDataReceived(_:)``
- ``noMoreMessages``
- ``messagesWaiting``

### Network Events

- ``advertisement(publicKey:)``
- ``pathUpdate(publicKey:)``
- ``acknowledgement(code:)``
- ``traceData(_:)``
- ``pathResponse(_:)``

### Authentication

- ``loginSuccess(_:)``
- ``loginFailed(publicKeyPrefix:)``

### Binary Protocol

- ``statusResponse(_:)``
- ``telemetryResponse(_:)``
- ``binaryResponse(tag:data:)``
- ``mmaResponse(_:)``
- ``aclResponse(_:)``
- ``neighboursResponse(_:)``

### Cryptographic Signing

- ``signStart(maxLength:)``
- ``signature(_:)``
- ``disabled(reason:)``

### Raw Data

- ``rawData(_:)``
- ``logData(_:)``
- ``rxLogData(_:)``
- ``controlData(_:)``
- ``discoverResponse(_:)``
- ``privateKey(_:)``

#### rxLogData(_:)

Indicates parsed RF log data.

- Parameter parsed: A ``ParsedRxLogData`` containing structured packet information.

Emitted when the device sends low-level radio log data that has been successfully
parsed. Malformed payloads fall back to ``logData(_:)`` instead.

> Note: Breaking change from prior versions which used `LogDataInfo`.

### Diagnostics

- ``parseFailure(data:reason:)``
