import SwiftUI

extension ClearanceStatus {
    var color: Color {
        switch self {
        case .clear: .green
        case .marginal: .yellow
        case .partialObstruction: .orange
        case .blocked: .red
        }
    }

    var iconName: String {
        switch self {
        case .clear: "checkmark.circle.fill"
        case .marginal, .partialObstruction: "exclamationmark.triangle.fill"
        case .blocked: "xmark.octagon.fill"
        }
    }

    var localizedName: String {
        switch self {
        case .clear: L10n.Tools.Tools.LineOfSight.Status.clear
        case .marginal: L10n.Tools.Tools.LineOfSight.Status.marginal
        case .partialObstruction: L10n.Tools.Tools.LineOfSight.Status.partialObstruction
        case .blocked: L10n.Tools.Tools.LineOfSight.Status.blocked
        }
    }

    static var blockedSubtitle: String {
        L10n.Tools.Tools.LineOfSight.Status.blockedSubtitle
    }
}
