# MeshCore One (MC1)

A MeshCore client built for iOS and iPadOS in Swift.   
Disclaimer: Built mostly with AI.

Join the beta on TestFlight or sideload using unsigned IPA files under [Releases](https://github.com/Avi0n/MeshCoreOne/releases).

<a href="https://testflight.apple.com/join/YjGNDTaE">
  <img src="https://img.shields.io/badge/Download_on-TestFlight-0D96F6?style=for-the-badge&logo=apple&logoColor=white" alt="Download on TestFlight">
</a>

## Screenshots

<table>
  <tr>
    <td><img width="200" alt="Chats" src="https://github.com/user-attachments/assets/1f6e061e-646d-4c1f-b813-6539df64e4f5"/></td>
    <td><img width="200" alt="Conversation" src="https://github.com/user-attachments/assets/d0fc7070-e9bd-4514-ab71-55fead4718e8"/></td>
    <td><img width="200" alt="Contacts" src="https://github.com/user-attachments/assets/96b77a6e-33a5-43c5-a112-e970fcb379a2"/></td>
    <td><img width="200" alt="Map" src="https://github.com/user-attachments/assets/af759be6-65ae-406c-ae62-ca44cfd468d6"/></td>
  </tr>
  <tr>
    <td><img width="200" alt="Settings" src="https://github.com/user-attachments/assets/f14b9d53-57b0-4a0c-9767-a0542d841d64"/></td>
    <td><img width="200" alt="Line of Sight" src="https://github.com/user-attachments/assets/f59652d9-81b1-4ceb-964b-cd2cd9ff7f49"/></td>
    <td><img width="200" alt="LoS Analysis" src="https://github.com/user-attachments/assets/e3f47192-682d-4e6a-88ca-b06be0f60e17"/></td>
    <td><img width="200" alt="RX Log" src="https://github.com/user-attachments/assets/24a7abc7-9dcc-4139-95a6-eada068b5dcd"/></td>
  </tr>
</table>

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
- Offline mesh networking (no internet required)
- Push notifications with quick reply
- Location sharing controls
- Config import/export


## Requirements

-   **iOS 18.0+**
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
