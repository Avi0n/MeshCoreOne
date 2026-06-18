import SwiftUI
import Charts
import MC1Services

struct SavedPathDetailView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @State private var viewModel: SavedPathDetailViewModel

    init(savedPath: SavedTracePathDTO) {
        _viewModel = State(initialValue: SavedPathDetailViewModel(savedPath: savedPath))
    }

    var body: some View {
        List {
            pathSection
                .themedRowBackground(theme)
            performanceSection
                .themedRowBackground(theme)
            historySection
                .themedRowBackground(theme)
        }
        .themedCanvas(theme)
        .navigationDestination(for: TracePathRoute.RunDetail.self) { route in
            RunDetailView(run: route.run)
        }
        .navigationTitle(viewModel.savedPath.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            viewModel.configure(dataStore: { appState.services?.dataStore })
        }
        .refreshable {
            await viewModel.refresh()
        }
    }

    // MARK: - Path Section

    private var pathSection: some View {
        Section(L10n.Contacts.Contacts.PathDetail.path) {
            PathChipsView(pathData: viewModel.savedPath.pathBytes, hashSize: viewModel.hashSize)
        }
    }

    // MARK: - Performance Section

    private var performanceSection: some View {
        Section(L10n.Contacts.Contacts.PathDetail.performance) {
            // Chart
            if viewModel.successfulRuns.count >= 2 {
                Chart(viewModel.successfulRuns) { run in
                    LineMark(
                        x: .value("Date", run.date),
                        y: .value("RTT", run.roundTripMs)
                    )
                    .foregroundStyle(.blue)

                    PointMark(
                        x: .value("Date", run.date),
                        y: .value("RTT", run.roundTripMs)
                    )
                    .foregroundStyle(.blue)
                }
                .frame(height: 150)
                .chartYAxisLabel(L10n.Contacts.Contacts.PathDetail.roundTripMs)
            }

            // Summary stats
            HStack {
                StatView(label: L10n.Contacts.Contacts.PathDetail.avg, value: viewModel.averageRoundTrip.map { "\($0) ms" } ?? "-")
                Divider()
                StatView(label: L10n.Contacts.Contacts.PathDetail.best, value: viewModel.bestRoundTrip.map { "\($0) ms" } ?? "-")
                Divider()
                StatView(label: L10n.Contacts.Contacts.PathDetail.success, value: viewModel.successRateText)
            }
            .frame(height: 50)
        }
    }

    // MARK: - History Section

    private var historySection: some View {
        Section(L10n.Contacts.Contacts.PathDetail.history) {
            ForEach(viewModel.sortedRuns) { run in
                NavigationLink(value: TracePathRoute.RunDetail(run: run)) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(run.date.formatted(date: .abbreviated, time: .shortened))
                            if run.success {
                                Text("\(run.roundTripMs) ms")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }

                        Spacer()

                        if !run.success {
                            Text(L10n.Contacts.Contacts.PathDetail.failed)
                                .font(.caption)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(.red.opacity(0.2), in: .capsule)
                                .foregroundStyle(.red)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Supporting Views

private struct PathChipsView: View {
    let pathData: Data
    let hashSize: Int

    private var hopHexStrings: [String] {
        stride(from: 0, to: pathData.count, by: hashSize).map { start in
            let end = min(start + hashSize, pathData.count)
            return pathData[start..<end].uppercaseHexString()
        }
    }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 4) {
                ForEach(Array(hopHexStrings.enumerated()), id: \.offset) { index, hex in
                    if index > 0 {
                        Image(systemName: "arrow.right")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    Text(hex)
                        .font(.caption.monospaced())
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.fill.tertiary, in: .capsule)
                }
            }
        }
        .scrollIndicators(.hidden)
    }
}

private struct StatView: View {
    let label: String
    let value: String

    var body: some View {
        VStack {
            Text(value)
                .font(.headline.monospacedDigit())
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct RunDetailView: View {
    @Environment(\.appTheme) private var theme
    let run: TracePathRunDTO

    var body: some View {
        List {
            Section(L10n.Contacts.Contacts.PathDetail.overview) {
                LabeledContent(L10n.Contacts.Contacts.PathDetail.date, value: run.date.formatted())
                LabeledContent(L10n.Contacts.Contacts.PathDetail.roundTrip, value: "\(run.roundTripMs) ms")
                LabeledContent(L10n.Contacts.Contacts.PathDetail.status, value: run.success ? L10n.Contacts.Contacts.PathDetail.success : L10n.Contacts.Contacts.PathDetail.failed)
            }
            .themedRowBackground(theme)

            if run.success && !run.hopsSNR.isEmpty {
                Section(L10n.Contacts.Contacts.PathDetail.perHopSNR) {
                    ForEach(Array(run.hopsSNR.enumerated()), id: \.offset) { index, snr in
                        LabeledContent(L10n.Contacts.Contacts.PathDetail.hop(index + 1)) {
                            Text(snr, format: .number.precision(.fractionLength(2)))
                            + Text(" dB")
                        }
                    }
                }
                .themedRowBackground(theme)
            }
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Contacts.Contacts.PathDetail.runDetails)
        .navigationBarTitleDisplayMode(.inline)
    }
}
