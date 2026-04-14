import Testing
import Foundation
@testable import MC1Services

@Suite("Contact OCV Tests")
struct ContactOCVTests {

    @Test("activeOCVArray returns Li-Ion by default")
    func activeOCVArrayReturnsLiIonByDefault() {
        let contact = ContactDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test",
            typeRawValue: ContactType.repeater.rawValue,
            flags: 0,
            outPathLength: 0xFF,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            ocvPreset: nil,
            customOCVArrayString: nil
        )

        #expect(contact.activeOCVArray == OCVPreset.liIon.ocvArray)
    }

    @Test("activeOCVArray returns preset array when set")
    func activeOCVArrayReturnsPresetWhenSet() {
        let contact = ContactDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test",
            typeRawValue: ContactType.repeater.rawValue,
            flags: 0,
            outPathLength: 0xFF,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            ocvPreset: OCVPreset.liFePO4.rawValue,
            customOCVArrayString: nil
        )

        #expect(contact.activeOCVArray == OCVPreset.liFePO4.ocvArray)
    }

    @Test("activeOCVArray returns custom array when valid")
    func activeOCVArrayReturnsCustomWhenValid() {
        let customArray = [4200, 4100, 4000, 3900, 3800, 3700, 3600, 3500, 3400, 3300, 3200]
        let customString = customArray.map(String.init).joined(separator: ",")

        let contact = ContactDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test",
            typeRawValue: ContactType.repeater.rawValue,
            flags: 0,
            outPathLength: 0xFF,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            ocvPreset: OCVPreset.custom.rawValue,
            customOCVArrayString: customString
        )

        #expect(contact.activeOCVArray == customArray)
    }

    @Test("activeOCVArray falls back to Li-Ion for invalid custom array")
    func activeOCVArrayFallsBackForInvalidCustom() {
        let contact = ContactDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test",
            typeRawValue: ContactType.repeater.rawValue,
            flags: 0,
            outPathLength: 0xFF,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            ocvPreset: OCVPreset.custom.rawValue,
            customOCVArrayString: "invalid"
        )

        #expect(contact.activeOCVArray == OCVPreset.liIon.ocvArray)
    }
}
