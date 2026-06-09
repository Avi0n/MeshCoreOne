import Foundation
import MC1Services

/// Pure-logic core for resolving a tapped `@mention`. Lives at file scope as an
/// `enum` to enable direct unit testing without instantiating a view.
/// `MentionTapHandler` is the one-line caller that performs the navigation side
/// effect for `.navigate` outcomes and presents the picker for `.picker`.
@MainActor
enum MentionTapEvaluator {
    enum Outcome {
        case navigate(ContactDTO)
        case picker(MentionPickerContext)
    }

    static func evaluate(
        rawName: String,
        contacts: [ContactDTO],
        connectedDeviceName: String?,
        radioID: UUID
    ) -> Outcome {
        let sanitized = MessageText.displayName(for: rawName)
        let trimmed = sanitized.trimmingCharacters(in: .whitespacesAndNewlines)

        if trimmed.isEmpty {
            return .picker(MentionPickerContext(
                name: sanitized,
                radioID: radioID,
                matches: [],
                isSelfMention: false
            ))
        }

        let isSelf: Bool = connectedDeviceName.map {
            sanitized.localizedCaseInsensitiveCompare($0) == .orderedSame
        } ?? false

        if isSelf {
            return .picker(MentionPickerContext(
                name: sanitized,
                radioID: radioID,
                matches: [],
                isSelfMention: true
            ))
        }

        let matches = SenderContactMatcher.filter(
            contacts: contacts,
            senderName: sanitized,
            excludeBlocked: false
        )

        if matches.count == 1 {
            return .navigate(matches[0])
        }

        return .picker(MentionPickerContext(
            name: sanitized,
            radioID: radioID,
            matches: matches,
            isSelfMention: false
        ))
    }
}
