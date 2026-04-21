# ``MeshCore``

A Swift library for communicating with MeshCore mesh networking devices over Bluetooth Low Energy.

## Overview

MeshCore provides a modern Swift API for controlling MeshCore devices from iOS and macOS applications. The library handles protocol encoding/decoding, session management, contact discovery, and event streaming.

**What you can do with MeshCore:**

- Connect to MeshCore devices via BLE
- Send and receive messages through the mesh network
- Discover and manage contacts
- Subscribe to real-time events (messages, advertisements, acknowledgements)
- Configure device settings (radio, coordinates, channels)
- Request telemetry and sensor data
- Execute binary protocol commands for advanced operations

## Topics

### Guides

- <doc:GettingStarted>
- <doc:SessionLifecycle>
- <doc:SendingMessages>
- <doc:ReceivingMessages>
- <doc:ManagingContacts>
- <doc:EventHandling>
- <doc:Telemetry>
- <doc:DeviceConfiguration>
- <doc:BinaryProtocol>
- <doc:CustomTransports>
- <doc:ProtocolInternals>

### Essentials

- ``MeshCoreSession``
- ``MeshTransport``
- ``SessionConfiguration``

### Communication

- ``ChannelDatagram``
- ``ContactMessage``
- ``ChannelMessage``
- ``MessageSentInfo``
- ``MessageResult``
- ``Destination``

### Contacts

- ``MeshContact``

### Events

- ``MeshEvent``
- ``EventDispatcher``
- ``EventFilter``
- ``ConnectionState``

### Device Information

- ``SelfInfo``
- ``DeviceCapabilities``
- ``BatteryInfo``

### Telemetry

- ``LPPDecoder``
- ``LPPEncoder``
- ``LPPSensorType``
- ``LPPDataPoint``
- ``LPPValue``
- ``TelemetryResponse``

### Network

- ``TraceInfo``
- ``PathInfo``
- ``NeighboursResponse``
- ``Neighbour``

### Statistics

- ``StatusResponse``
- ``CoreStats``
- ``RadioStats``
- ``PacketStats``

### Binary Protocol

- ``MMAResponse``
- ``ACLResponse``
- ``BinaryRequestType``

### Channels

- ``ChannelInfo``
- ``ChannelSecret``
- ``DefaultFloodScope``
- ``FloodScope``

### Protocol Internals

- ``PacketBuilder``
- ``PacketParser``
- ``CommandCode``
- ``ResponseCode``

### Errors

- ``MeshCoreError``
- ``MeshTransportError``
- ``DestinationError``

### Transport Implementations

- ``BLETransport``
- ``MockTransport``
