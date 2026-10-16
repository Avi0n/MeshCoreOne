import Foundation

/// Represents a contact stored on the MeshCore device.
///
/// `MeshContact` defines a node in the mesh network that your device has discovered or stored.
/// Contacts are typically discovered through advertisements and are used as message destinations.
///
/// ## Identity
/// Each contact has a unique 32-byte public key. The ``id`` property
/// is the hex string representation for use with SwiftUI's `Identifiable`.
///
/// ## Routing
/// The ``outPath`` and ``outPathLength`` describe the routing path to reach
/// this contact. A path length of `0xFF` indicates flood routing (broadcast to all).
///
/// The ``outPathLength`` byte uses bit packing to encode both the hash size and hop count:
/// - Upper 2 bits (6-7): hash size mode (0=1-byte, 1=2-byte, 2=3-byte, 3=reserved)
/// - Lower 6 bits (0-5): hop count (0-63)
///
/// Use ``pathHashSize``, ``pathHopCount``, and ``pathByteLength`` to decode these fields.
///
/// ## Location
/// If the contact shares its location, ``latitude`` and ``longitude``
/// contain GPS coordinates.
///
/// ## Usage
/// ```swift
/// // Find a contact by name
/// if let contact = session.getContactByName("MyNode") {
///     try await session.sendMessage(to: contact.publicKey, text: "Hello!")
/// }
///
/// // Check routing mode
/// if contact.isFloodPath {
///     print("\(contact.advertisedName) uses flood routing")
/// }
/// ```
public struct MeshContact: Sendable, Identifiable, Equatable {
  /// The unique identifier for the contact, represented as a hex string of the public key.
  public let id: String

  /// The contact's 32-byte public key.
  public let publicKey: Data

  /// The type identifier for the contact.
  public let type: ContactType

  /// The raw 1-byte type value as it appears on the wire.
  ///
  /// Normally equal to `type.rawValue`. Preserved separately so a contact carrying a
  /// type byte not yet modeled by ``ContactType`` (e.g. from newer firmware or an
  /// imported config) survives instead of being coerced to `.chat`. The byte round-trips
  /// through OTA decode, the local cache, config export, and the config-import /
  /// contact-add device write (``addContact`` encodes via ``PacketBuilder/updateContact(_:)``).
  ///
  /// Two honest exclusions: `changeContactPath` and `changeContactFlags` re-coerce the type
  /// on a user-initiated path/flag edit (they encode through the typed
  /// `updateContact(publicKey:type:…)` overload), and `lastModified` is restamped by the
  /// firmware on receipt because the frame is under 148 bytes — so "verbatim" covers the
  /// type byte, not the device-side advert/modified timestamps.
  public let typeRawValue: UInt8

  /// The operational flags for the contact.
  public let flags: ContactFlags

  /// The encoded outbound path length byte.
  ///
  /// Uses bit packing: upper 2 bits = hash size mode, lower 6 bits = hop count.
  /// A value of `0xFF` indicates flood routing (unknown path).
  public let outPathLength: UInt8

  /// The outbound routing path data.
  public let outPath: Data

  /// The name this contact advertises on the network.
  public let advertisedName: String

  /// The date and time when this contact last sent an advertisement.
  public let lastAdvertisement: Date

  /// The latitude coordinate of the contact, if location sharing is enabled.
  public let latitude: Double

  /// The longitude coordinate of the contact, if location sharing is enabled.
  public let longitude: Double

  /// The date and time when this contact record was last modified.
  public let lastModified: Date

  /// Computes the first 6 bytes of the public key as a hex string.
  ///
  /// This prefix is commonly used for UI display and as a compact message destination.
  public var publicKeyPrefix: String {
    publicKey.prefix(6).hexString
  }

  /// Indicates whether this contact uses flood (broadcast) routing.
  ///
  /// Flood routing sends messages to all nodes in the network. This is used when
  /// no direct path is known. Represented by `0xFF` on the wire.
  public var isFloodPath: Bool {
    outPathLength == 0xFF
  }

  /// The hash size per hop in bytes (1, 2, or 3), derived from the upper 2 bits of ``outPathLength``.
  ///
  /// Only meaningful when ``isFloodPath`` is `false`.
  public var pathHashSize: Int {
    decodePathLen(outPathLength)?.hashSize ?? 1
  }

  /// The number of hops in the path, derived from the lower 6 bits of ``outPathLength``.
  ///
  /// Only meaningful when ``isFloodPath`` is `false`.
  public var pathHopCount: Int {
    decodePathLen(outPathLength)?.hopCount ?? 0
  }

  /// The total byte length of the path data (`pathHopCount * pathHashSize`).
  ///
  /// Only meaningful when ``isFloodPath`` is `false`.
  public var pathByteLength: Int {
    decodePathLen(outPathLength)?.byteLength ?? 0
  }

  /// Initializes a new mesh contact with the specified properties.
  ///
  /// - Parameters:
  ///   - id: Unique hex string identifier.
  ///   - publicKey: The 32-byte public key data.
  ///   - type: Contact type identifier (chat, repeater, room).
  ///   - typeRawValue: Raw wire type byte. Defaults to `type.rawValue`; pass an explicit
  ///     value to preserve a byte not modeled by ``ContactType``.
  ///   - flags: Operational flags (favorite, telemetry permissions).
  ///   - outPathLength: Encoded outbound path length byte.
  ///   - outPath: Outbound path data.
  ///   - advertisedName: Name advertised by the node.
  ///   - lastAdvertisement: Date of last advertisement.
  ///   - latitude: Latitude coordinate.
  ///   - longitude: Longitude coordinate.
  ///   - lastModified: Date of last record update.
  public init(
    id: String,
    publicKey: Data,
    type: ContactType,
    typeRawValue: UInt8? = nil,
    flags: ContactFlags,
    outPathLength: UInt8,
    outPath: Data,
    advertisedName: String,
    lastAdvertisement: Date,
    latitude: Double,
    longitude: Double,
    lastModified: Date
  ) {
    self.id = id
    self.publicKey = publicKey
    self.type = type
    self.typeRawValue = typeRawValue ?? type.rawValue
    self.flags = flags
    self.outPathLength = outPathLength
    self.outPath = outPath
    self.advertisedName = advertisedName
    self.lastAdvertisement = lastAdvertisement
    self.latitude = latitude
    self.longitude = longitude
    self.lastModified = lastModified
  }
}
