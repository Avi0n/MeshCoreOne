/// Result of parsing and adding repeater codes
struct CodeInputResult {
    var added: [String] = []
    var notFound: [String] = []
    var alreadyInPath: [String] = []
    var invalidFormat: [String] = []

    var hasErrors: Bool {
        !notFound.isEmpty || !alreadyInPath.isEmpty || !invalidFormat.isEmpty
    }

    var errorMessage: String? {
        guard hasErrors else { return nil }

        var parts: [String] = []

        if !invalidFormat.isEmpty {
            parts.append(L10n.Contacts.Contacts.CodeInput.Error.invalidFormat(invalidFormat.joined(separator: ", ")))
        }
        if !notFound.isEmpty {
            parts.append(L10n.Contacts.Contacts.CodeInput.Error.notFound(notFound.joined(separator: ", ")))
        }
        if !alreadyInPath.isEmpty {
            parts.append(L10n.Contacts.Contacts.CodeInput.Error.alreadyInPath(alreadyInPath.joined(separator: ", ")))
        }

        return parts.joined(separator: " · ")
    }
}
