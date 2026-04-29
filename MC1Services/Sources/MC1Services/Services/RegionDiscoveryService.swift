import Foundation
import MeshCore

/// Discovers and tracks *firmware-mesh regions* — named flood-routing scopes
/// advertised by repeaters via `DISCOVER_REQ` / 0x04 filter.
///
/// Vocabulary note: `RegionalAreas` and `RegionResolver` (introduced in the
/// onboarding redesign) use "region" to mean *user geographic location*. These
/// two concepts share a word but no semantic overlap.
public enum RegionDiscoveryService {

    /// Filter value for DISCOVER_REQ that requests only repeaters.
    /// Mirrors `NodeDiscoveryFilter.repeaters.filterValue` in the app target.
    public static let repeatersFilter: UInt8 = 0x04

    /// How long to listen for DISCOVER_RESP before closing the event stream.
    /// Matches the scan duration used by NodeDiscoveryViewModel so flood-routed
    /// responses from distant mesh nodes have time to arrive.
    public static let listenDuration: Duration = .seconds(15)

    /// Firmware error code for "contact table full" responses (`ERR_CODE_TABLE_FULL`).
    private static let tableFullErrorCode: UInt8 = 3

    public enum Outcome: Sendable {
        /// The DISCOVER_REQ itself failed to send.
        case sendFailed
        /// No repeaters responded to the probe, or no query targets could be built.
        case noRepeatersResponded
        /// Could not load the contact pool used to route region queries.
        case errorLoadingRepeaters
        /// Discovery completed. `newRegions` is filtered against the caller's
        /// `knownRegions` and sorted. `allRepeatersTableFull` is `true` when every
        /// successful query returned `ERR_CODE_TABLE_FULL` from the repeater.
        case completed(newRegions: [String], allRepeatersTableFull: Bool)
    }

    /// Runs the discovery probe and aggregates regions from all responders.
    ///
    /// - Parameters:
    ///   - session: Active mesh session for the connected device.
    ///   - contactService: Source of the caller's existing contacts; used to find
    ///     routing data for repeaters the user has already added.
    ///   - dataStore: Optional offline data store; when provided, responders that
    ///     aren't contacts are matched against the discovered-nodes table.
    ///   - radioID: Radio identifier used to scope contact and discovered-node lookups.
    ///   - knownRegions: Regions the caller already knows about; subtracted from the result.
    public static func discover(
        session: MeshCoreSession,
        contactService: ContactService,
        dataStore: PersistenceStore?,
        radioID: UUID,
        knownRegions: [String]
    ) async -> Outcome {
        let discoveredPubkeys: Set<Data>
        do {
            let tag = try await session.sendNodeDiscoverRequest(
                filter: repeatersFilter,
                prefixOnly: false
            )
            let tagData = withUnsafeBytes(of: tag.littleEndian) { Data($0) }

            let (subscriptionID, events) = await session.eventsTracked()
            let listenTask = Task { () -> Set<Data> in
                var keys = Set<Data>()
                for await event in events {
                    if case .discoverResponse(let response) = event,
                       response.tag == tagData {
                        keys.insert(response.publicKey)
                    }
                }
                return keys
            }

            try? await Task.sleep(for: listenDuration)
            await session.finishEvents(id: subscriptionID)
            discoveredPubkeys = await listenTask.value
        } catch {
            return .sendFailed
        }

        guard !Task.isCancelled else { return .sendFailed }

        if discoveredPubkeys.isEmpty {
            return .noRepeatersResponded
        }

        let queryTargets: [MeshContact]
        do {
            let contacts = try await contactService.getContacts(radioID: radioID)
            let discoveredNodes = (try? await dataStore?.fetchDiscoveredNodes(radioID: radioID)) ?? []
            queryTargets = buildRegionQueryTargets(
                responders: discoveredPubkeys,
                contacts: contacts,
                discoveredNodes: discoveredNodes
            )
        } catch {
            return .errorLoadingRepeaters
        }

        if queryTargets.isEmpty {
            return .noRepeatersResponded
        }

        var allRegions = Set<String>()
        var anyTableFull = false

        await withTaskGroup(of: RegionQueryOutcome.self) { group in
            for meshContact in queryTargets {
                guard !Task.isCancelled else { break }
                group.addTask {
                    do {
                        let regions = try await session.requestRegions(from: meshContact)
                        return .regions(regions)
                    } catch let MeshCoreError.deviceError(code) where code == tableFullErrorCode {
                        return .tableFull
                    } catch {
                        return .otherFailure
                    }
                }
            }
            for await outcome in group {
                switch outcome {
                case .regions(let regions):
                    allRegions.formUnion(regions)
                case .tableFull:
                    anyTableFull = true
                case .otherFailure:
                    break
                }
            }
        }

        let knownSet = Set(knownRegions)
        let newRegions = allRegions.subtracting(knownSet).sorted()
        return .completed(newRegions: newRegions, allRepeatersTableFull: anyTableFull)
    }

    /// Builds the `MeshContact` query pool used to fetch regions from each responder.
    /// Prefers contact records (they carry direct routing data when available) and fills
    /// in from the discovered-nodes table for responders the user has not added as contacts.
    static func buildRegionQueryTargets(
        responders: Set<Data>,
        contacts: [ContactDTO],
        discoveredNodes: [DiscoveredNodeDTO]
    ) -> [MeshContact] {
        var byKey: [Data: MeshContact] = [:]
        for contact in contacts where contact.type == .repeater && responders.contains(contact.publicKey) {
            byKey[contact.publicKey] = contact.toContactFrame().toMeshContact()
        }
        for node in discoveredNodes where node.nodeType == .repeater
            && responders.contains(node.publicKey)
            && byKey[node.publicKey] == nil {
            let frame = ContactFrame(
                publicKey: node.publicKey,
                type: node.nodeType,
                flags: 0,
                outPathLength: node.outPathLength,
                outPath: node.outPath,
                name: node.name,
                lastAdvertTimestamp: node.lastAdvertTimestamp,
                latitude: node.latitude,
                longitude: node.longitude,
                lastModified: 0
            )
            byKey[node.publicKey] = frame.toMeshContact()
        }
        return Array(byKey.values)
    }

    private enum RegionQueryOutcome: Sendable {
        case regions([String])
        case tableFull
        case otherFailure
    }
}
