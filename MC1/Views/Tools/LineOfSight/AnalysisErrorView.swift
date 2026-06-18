import SwiftUI

struct AnalysisErrorView: View {
    let message: String
    let hasRepeater: Bool
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(.orange)

            Text(L10n.Tools.Tools.LineOfSight.analysisFailed)
                .font(.headline)

            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button(L10n.Tools.Tools.LineOfSight.retry) {
                onRetry()
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity)
        .padding()
    }
}
