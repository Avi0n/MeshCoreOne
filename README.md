# MeshCore One (MC1)

A MeshCore client built for Apple devices in Swift.
Disclaimer: Decisions are made by a human, but almost all code is created with AI.

Join the beta on TestFlight or sideload using unsigned IPA files under [Releases](https://github.com/Avi0n/MeshCoreOne/releases).

<a href="https://testflight.apple.com/join/YjGNDTaE">
  <img src="https://img.shields.io/badge/Download_on-TestFlight-0D96F6?style=for-the-badge&logo=apple&logoColor=white" alt="Download on TestFlight">
</a>

## Features

### Messaging
- Direct messages with delivery status and flood retry
- Channels (public, private, and hashtag)
- Room Server connections with guest/participant modes
- Heard repeats tracking
- Message reactions (emoji)
- Path Hops
- Link previews and inline images
- @Mentions
- Per-conversation notification levels
- Hashtag channel deep links
- Blocking (contacts and channel senders)

### Contacts
- Auto-discovery on the mesh
- QR code and advert sharing
- Favorites
- Ping repeater (latency and SNR)

### Map
- Contact positions
- Map layers (standard, satellite, hybrid)
- Offline download

### Network Tools
- **Trace Path** - Route through specific repeaters with option to save paths
- **Line of Sight** - Terrain analysis with Fresnel zone and RF parameters
- **RX Log** - Live packet capture
- **Noise Floor Monitor** - Live dBm chart with signal quality stats
- **CLI Terminal** - Remote command-line access to repeaters and rooms

### Remote Node Management
- Node status (telemetry such as battery and uptime. Neighbors for repeaters)
- Remote repeater/room configuration (radio, behavior, identity, reboot)
- Telemetry history charts
- Admin and guest authentication for repeaters/rooms

### Companion Device
- Bluetooth and WiFi pairing
- Radio presets and manual tuning (frequency, TX power, spreading factor, bandwidth)
- Battery monitoring with OCV curves

### General
- Live Activity
- Offline mesh networking (no internet required)
- Push notifications with quick reply
- Location sharing controls
- Config import/export


## Requirements

-   **iOS/iPadOS 18.0+, or Apple Silicon Mac**
-   **Xcode 26.0+**
-   **MeshCore-compatible hardware**

## Getting Started

1.  Install [XcodeGen](https://github.com/yonaskolb/XcodeGen).
2.  Run `xcodegen generate`.
3.  Open `MC1.xcodeproj`.

For more details, see the [Development Guide](docs/Development.md).

  
## License

MeshCore One - GNU General Public License v3.0   
Swift MeshCore - MIT
