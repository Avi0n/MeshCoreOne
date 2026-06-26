# Glossary

Domain-specific terms and acronyms used throughout the MeshCore One codebase.

## Radio & Protocol

| Term | Definition |
|------|-----------|
| **BLE** | Bluetooth Low Energy — the wireless transport used to connect the iOS app to the local mesh radio hardware. |
| **BW** | Bandwidth — the LoRa channel bandwidth in kHz (e.g., 62.5, 250) that determines spectral occupancy and affects range and data rate. |
| **CR** | Coding Rate — a LoRa forward error correction parameter (e.g., 4/5 through 4/8) that adds redundancy to improve reliability at the cost of throughput. |
| **LoRa** | Long Range — the sub-GHz spread-spectrum radio modulation technology used by the mesh hardware to communicate between nodes. |
| **LPP** | Low Power Payload (Cayenne) — a binary serialization format used to encode and decode structured sensor telemetry (temperature, GPS, pressure, voltage, etc.) transmitted over the mesh. |
| **RSSI** | Received Signal Strength Indicator — the raw received radio signal strength in dBm, reported per received packet alongside SNR. |
| **Rx / Tx** | Receive / Transmit — used to denote radio direction, e.g., `rxAirtimeSeconds` tracks how long the device's radio has been actively receiving. |
| **SF** | Spreading Factor — a LoRa modulation parameter (valid range 5–12) that controls the chirp rate, trading range for data rate. |
| **SNR** | Signal-to-Noise Ratio — a radio quality metric in dB reported per received packet, used to indicate link quality between mesh nodes. |
| **TC** | Transport Code — a 4-byte identifier embedded in certain route types (`tcFlood`, `tcDirect`) in the RF packet header for routing purposes. |

## Mesh Network

| Term | Definition |
|------|-----------|
| **ACL** | Access Control List — a list of authorized public key prefixes and their permission levels (guest, read-write, admin) on a remote node. |
| **advert** | Advertisement — a mesh packet broadcast by a node to announce its presence and identity on the network. |
| **CLI** | Command-Line Interface — a text-based command channel sent over the mesh to a remote repeater or room server for admin operations. |
| **DM** | Direct Message — a one-to-one encrypted message sent between two mesh nodes, as opposed to a channel/group message. |
| **MMA** | Min/Max/Average — a binary protocol request type that retrieves aggregated time-series statistics for sensor telemetry from a remote node. |

## App Architecture

| Term | Definition |
|------|-----------|
| **DTO** | Data Transfer Object — a plain Swift struct used to carry data between service layers and persistence, decoupled from SwiftData model objects. |
| **OCV** | Open Circuit Voltage — a battery characterization table (millivolt array or named preset like `liIon`/`liFePO4`) used to estimate battery percentage from resting voltage. |
