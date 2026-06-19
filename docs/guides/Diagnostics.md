# Diagnostics Guide

This guide explains MeshCore One's network diagnostic tools for optimizing mesh network performance and troubleshooting connectivity issues.

## Overview

MeshCore One includes three powerful diagnostic tools:

1. **Line of Sight (LoS)** - Analyze radio propagation and terrain clearance between two points
2. **Trace Path** - Discover and save optimal routing paths through your mesh network
3. **RX Log** - Monitor live RF traffic and packet capture

---

## Line of Sight Analysis

Line of Sight analysis helps determine if a reliable RF link is possible between two points by analyzing terrain elevation and calculating Fresnel zone clearance.

### Accessing Line of Sight Tool

1. Go to **Tools** tab
2. Tap **Line of Sight**
3. Drop or select the two points (A and B) to analyze on the map
4. Tap **Analyze**

Line of Sight runs entirely offline and does not require a connected radio.

### Understanding the Analysis

The Line of Sight tool provides:

#### Terrain Profile

A visual representation of the terrain elevation between you and the target:

- **X-Axis**: Distance from starting point (in meters)
- **Y-Axis**: Elevation (in meters)
- **Your Location**: Shown as blue dot at left edge
- **Target Location**: Shown as blue dot at right edge
- **Terrain Line**: Black line showing actual ground elevation
- **Line of Sight**: Green line indicating direct line-of-sight path

#### Fresnel Zones

The Fresnel zone is the area around the direct line-of-sight path where radio signals propagate. A clear Fresnel zone is critical for reliable communication:

- **First Fresnel Zone**: Most critical zone (60% of signal power)
- **Secondary Zones**: Less critical zones shown as dashed lines
- **Zone Calculation**: Depends on frequency and distance

**Fresnel Zone Formula**:
```
r = 17.32 × sqrt((d1 × d2) / (f × D))

Where:
- r = Fresnel zone radius (meters)
- d1 = Distance from point A to obstacle (meters)
- d2 = Distance from obstacle to point B (meters)
- f = Frequency (GHz)
- D = Total distance between A and B (meters)
```

#### Clearance Status

Color-coded indicators show link quality:

- **🟢 Green**: Clear line of sight, >60% Fresnel zone clearance expected
  - Signal quality: Excellent
  - Expected connectivity: 95-100%
  - Recommendations: No action needed

- **🟡 Yellow**: Partial obstruction, 20-60% Fresnel zone clearance
  - Signal quality: Fair to good
  - Expected connectivity: 60-95%
  - Recommendations: Increase height, use repeaters, try alternative path

- **🔴 Red**: Obstructed path, <20% Fresnel zone clearance
  - Signal quality: Poor
  - Expected connectivity: 0-60%
  - Recommendations: Significantly increase height, use multiple repeaters, relocate equipment

#### RF Parameters

Calculated signal metrics for the proposed link:

**Path Loss**:
- Expected signal attenuation in decibels (dB)
- Depends on frequency, distance, and terrain
- Lower values indicate better expected signal

**Signal Strength**:
- Estimated received signal power at target (in dBm)
- Calculated from transmit power minus path loss
- Typical values:
  - Excellent: -50 to -60 dBm
  - Good: -60 to -70 dBm
  - Fair: -70 to -80 dBm
  - Poor: -80 to -90 dBm
  - Unusable: < -90 dBm

**First Fresnel Zone Clearance**:
- Percentage of Fresnel zone that is clear of obstructions
- Critical metric for link quality
- >60%: Clear, 20-60%: Partial, <20%: Obstructed

**Maximum Range**:
- Theoretical maximum communication distance
- Based on free-space path loss formula
- Assumes ideal conditions; real-world range will be less

### Tips for Better Line of Sight

**Physical Improvements**:
- **Elevation**: Move to higher ground (hills, buildings)
- **Antenna Height**: Increase antenna height at both ends
- **Antenna Gain**: Use directional or high-gain antennas
- **Avoid Obstacles**: Clear trees, buildings, or move equipment around obstacles

**Network Improvements**:
- **Use Repeaters**: Add repeaters to bypass major obstructions
- **Optimal Pathing**: Try multiple paths to find best route
- **Frequency Selection**: Lower frequencies have better diffraction (go around obstacles)

**Analysis Best Practices**:
- **Check Weather**: Rain and humidity can affect RF propagation
- **Test Real-World**: Analysis provides estimates; actual testing is essential
- **Document Results**: Save analysis for future reference and comparison

### Elevation Data

- **Source**: Open-Meteo API (open-source terrain data)
- **Resolution**: ~90m grid
- **Accuracy**: Within ±10m for most areas
- **Offline Use**: After initial fetch, cached elevation data is used for analysis
- **API Limitations**: May not have data for some remote areas

### Technical Implementation

**Line of Sight Algorithm**:
1. Generate elevation samples along the path based on total distance
2. Calculate direct line-of-sight line between endpoints
3. For each sample point, check if terrain elevation exceeds line-of-sight elevation
4. Calculate Fresnel zone radius at each sample point
5. Determine percentage of path with clear Fresnel zone
6. Apply Earth curvature (bulge) correction at each sample point

**Fresnel Zone Calculation**:
- Frequency-dependent (higher frequency = smaller Fresnel zone)
- Ellipsoid-shaped zone, widest at midpoint
- First Fresnel zone is most critical (contains 60% of signal energy)

---

## Trace Path

Trace Path discovers optimal routing paths through your mesh network by analyzing available repeaters and signal quality.

### Accessing Trace Path

1. Go to **Tools** tab
2. Tap **Trace Path**
3. Build a path by selecting and ordering repeaters (hops)
4. Run the trace and review signal quality

### Understanding Trace Path Results

#### Path Information

Each discovered path shows:

**Total Hops**:
- Number of repeaters between you and target
- Fewer hops = lower latency
- More hops = higher reliability but potential delays

**Signal Quality**:
- Signal-to-noise ratio (SNR) reported per hop
- Color-coded (shared signal-quality scale):
  - 🟢 Green: SNR > 0 dB (good or excellent)
  - 🟡 Yellow: SNR > -6 dB (fair)
  - 🔴 Red: SNR ≤ -6 dB (poor)

**Total Distance**:
- Sum of distances for all hops
- Longer distances have higher path loss

#### Per-Hop Details

Each hop in the result lists:

**Node Identity**:
- Resolved contact name when known, otherwise the public-key hash prefix
- A status label: started (your device), repeated (relay), or received (target)

**Signal Metrics**:
- **SNR**: Signal-to-noise ratio for the hop (higher is better), shown with a signal-bars indicator
- When the trace is run repeatedly (batch mode), the SNR is shown as average with min/max range

### Saving Paths

Save useful paths for future use:

1. Review discovered path results
2. Tap **Save Path** button
3. Enter path name (e.g., "Home to Office - Route A")
4. Path is saved for quick access later

### Managing Saved Paths

1. Go to **Tools** tab
2. Tap **Trace Path**, then the **Saved Paths** (bookmark) button in the toolbar
3. View all saved paths with statistics:
   - Path name
   - Total hops and distance
   - Last used date
   - Signal quality summary

4. Tap a saved path to:
   - View detailed route on map
   - See per-hop signal metrics
   - Edit path (change repeaters)
   - Delete path

5. Tap **Edit** to modify path:
   - Change repeaters in the route
   - Add or remove hops
   - Update path name

### Path Discovery Algorithm

Trace Path focuses on operator-controlled routing:

1. You select and order repeaters (hops) to build a path
2. The app sends trace/path requests to validate connectivity and measure signal quality
3. You can save working paths for later reuse

### Tips for Better Paths

**Optimizing for Speed**:
- Choose paths with fewer hops
- Higher SNR per hop = faster retransmissions
- Avoid congested repeaters

**Optimizing for Reliability**:
- Higher average SNR = better reliability
- Shorter hops = lower per-hop failure rate
- Use multiple redundant paths

**Network Planning**:
- Save multiple paths for common destinations
- Test paths in different weather conditions
- Document which paths work best for different times of day

---

## RX Log Viewer

RX Log viewer captures and displays live RF traffic for network debugging and analysis.

### Accessing RX Log Viewer

1. Go to **Tools** tab
2. Tap **RX Log**
3. Viewer starts capturing packets automatically

### Understanding RX Log

Each packet entry is a collapsible row. Collapsed, it shows route type, time, path, and signal; expanded, it adds detail rows.

**Timestamp**:
- When packet was received
- Newest packets appear at the top

**Route Type**:
- Routing mode parsed from the packet header: FLOOD, DIRECT, TC_FLOOD, or TC_DIRECT
- Flood routes are tinted green; direct routes blue

**Path**:
- The hop path visualization, with each hop shown as a public-key prefix (or "You" for the local device)
- Direct (no path) packets are labelled accordingly
- For direct text messages, the From → To prefixes are also shown

**Packet Type**:
- The payload type parsed from the header, shown by its short name, for example:
  - **TEXT_MSG**: User text message
  - **ACK**: Acknowledgment packet
  - **ADVERT**: Advertisement
  - **GROUP_TEXT** / **GROUP_DATA**: Channel/group traffic
  - **PATH**, **TRACE**, **REQUEST**, **RESPONSE**, **ANON_REQ**, **MULTIPART**, **CONTROL**, **RAW_CUSTOM**, **UNKNOWN**

**Signal Metrics**:
- **RSSI**: Received signal strength indicator (dBm, closer to 0 is better)
- **SNR**: Signal-to-noise ratio (dB, higher is better)
- A signal-bars glyph reflects the SNR-based quality classification:
  - 🟢 Excellent: SNR > +6 dB
  - 🟢 Good: SNR > 0 dB
  - 🟡 Fair: SNR > -6 dB
  - 🔴 Poor: SNR ≤ -6 dB

**Payload**:
- Decoded message text is shown when the packet decrypts successfully
- Otherwise the row shows the payload type and byte size
- The expanded row includes the raw payload as hex (truncated, with a Copy button) plus packet hash, and channel info when decrypted

### RX Log Features

#### Live Capture

- **Live Status**: A status pill shows whether capture is live (connected) or offline, with a packet counter
- **Newest First**: New packets are inserted at the top of the list
- **Group Duplicates**: Collapse repeated copies of the same packet into a single row with a count badge
- **Delete Logs**: Clear all captured packets (confirmation required)

#### Filtering

Filter logs from the toolbar filter menu:

- **Route Type**: All, Flood Only, or Direct Only
- **Decrypt Status**: All, Decrypted, or Failed

### Using RX Log for Troubleshooting

**Detecting Network Issues**:

**Packet Loss**:
- Look for gaps in sequence numbers
- Check for missing ACKs after sends
- High NACK rate indicates reliability issues

**Interference**:
- Fluctuating RSSI/SNR values
- High noise floor (low SNR even when RSSI is good)
- Pattern of failures at specific times

**Congestion**:
- High packet rate from specific nodes
- Collision indicators (packets with high retry counts)
- Latency spikes during high-traffic periods

**Routing Issues**:
- Packets taking unexpected routes
- Suboptimal hop counts to common destinations
- Nodes not advertising routes they should have

### RX Log Implementation

**Packet Capture**:
- Listens to the radio's RX log event stream
- Captures all received packets (including failed decodes)
- Persists entries to the local database (scoped per radio), pruning old entries to a recent cap; the live view also holds the most recent entries in memory

**Performance Considerations**:
- **Memory**: Log entries are lightweight (~200 bytes each)
- **CPU**: Minimal impact (packet parsing is shared with normal operation)
- **Battery**: Negligible impact (capture is passive)
- **Storage**: Limited to recent logs to prevent disk bloat

---

## Debug Logging

MeshCore One includes persistent debug logging for troubleshooting.

### Exporting Logs

1. Go to **Settings** tab
2. Scroll to **Diagnostics** section
3. Tap **Export Debug Logs**

The export includes the last 24 hours of logs (up to 1,000 entries) plus app/device metadata.

---

## Best Practices

### When to Use Diagnostic Tools

**Line of Sight**:
- Planning new node placements
- Troubleshooting poor signal to specific location
- Designing optimal network topology
- Before installing permanent equipment

**Trace Path**:
- Finding optimal routes for critical communications
- Understanding network topology
- Planning redundant paths for reliability
- Documenting network configuration

**RX Log**:
- Debugging intermittent connectivity issues
- Analyzing network traffic patterns
- Investigating packet loss or interference
- Verifying message delivery

### Documentation

Always document your diagnostic findings:

- **Date and Time**: When analysis was performed
- **Conditions**: Weather, time of day, other factors
- **Results**: All metrics and observations
- **Photos**: Screenshots or photos of equipment placement
- **Actions Taken**: Changes made based on analysis

### Limitations

**Terrain Data**:
- Elevation data may not be current (construction, vegetation changes)
- Resolution may miss small obstacles
- Underground or indoor obstacles not detected

**RF Calculations**:
- Provide theoretical estimates, not guarantees
- Assume ideal propagation conditions
- Don't account for multipath interference
- Don't account for weather effects (rain, humidity)

**Path Discovery**:
- Depends on known network topology
- Can't discover paths through unknown nodes
- Static analysis; real-world conditions may differ

---

## Further Reading

- [Architecture Overview](../Architecture.md)
- [Development Guide](../Development.md)
- [User Guide](../User_Guide.md)
