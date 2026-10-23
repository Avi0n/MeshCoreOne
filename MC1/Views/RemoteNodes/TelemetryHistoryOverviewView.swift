import MC1Services
import SwiftUI

/// Offline-accessible overview of all historical telemetry charts for a repeater.
struct TelemetryHistoryOverviewView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var state: TelemetryHistoryOverviewState
  @State private var rendererID = UUID()
  @State private var hasRestoredPosition = false
  @State private var restorationTarget: TelemetryHistoryOverviewState.ScrollID?
  @State private var visiblePosition: TelemetryHistoryOverviewState.ScrollID?
  private let ownsState: Bool
  private let rendererRevision: Int
  private let openMap: (([NodeStatusSnapshotDTO], UUID?) -> Void)?

  private var viewModel: TelemetryHistoryOverviewViewModel {
    state.viewModel
  }

  init(publicKey: Data, radioID: UUID, showNeighbors: Bool = true) {
    _state = State(initialValue: TelemetryHistoryOverviewState(history: .init(
      publicKey: publicKey, radioID: radioID, showNeighbors: showNeighbors
    )))
    ownsState = true
    rendererRevision = 0
    openMap = nil
  }

  init(
    state: TelemetryHistoryOverviewState,
    rendererRevision: Int = 0,
    openMap: (([NodeStatusSnapshotDTO], UUID?) -> Void)? = nil
  ) {
    _state = State(initialValue: state)
    ownsState = false
    self.rendererRevision = rendererRevision
    self.openMap = openMap
  }

  var body: some View {
    @Bindable var state = state
    @Bindable var viewModel = state.viewModel
    let filtered = viewModel.filteredSnapshots
    ScrollViewReader { proxy in
      List {
        if !viewModel.hasSnapshots {
          emptyState
        } else {
          timeRangeSection(selection: $viewModel.timeRange)
          radioSection(filtered: filtered)
          sensorsSection(filtered: filtered)
          LocationHistorySection(
            snapshots: filtered,
            showsFullPath: true,
            showsMap: $state.showsLocationMap,
            pendingMapSelection: $state.selectedReportID,
            openMap: openMap,
            onVisiblePosition: recordVisiblePosition
          )
          if state.history.showNeighbors {
            neighborsSection(filtered: filtered)
          }
          retentionFooter
        }
      }
      .coordinateSpace(name: TelemetryHistoryOverviewState.scrollCoordinateSpace)
      .listSectionSpacing(.compact)
      .themedCanvas(theme)
      .chartScrubbingScrollLock()
      .navigationTitle(L10n.RemoteNodes.RemoteNodes.History.overviewTitle)
      .liquidGlassToolbarBackground()
      .locationMapDestination(
        isPresented: $state.showsLocationMap,
        snapshots: filtered,
        initialSelection: state.selectedReportID
      )
      .onAppear {
        state.prepareRenderer(rendererID, revision: rendererRevision)
        if ownsState { state.configure(appState: appState) }
      }
      .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
        guard size.width > 0, size.height > 0 else { return }
        restorePosition(using: proxy)
      }
      .onChange(of: state.hasLoaded) { _, _ in restorePosition(using: proxy) }
      .onChange(of: viewModel.timeRange) { _, _ in
        guard hasRestoredPosition else { return }
        let target = state.restoredPosition(in: filtered)
        if target != state.visibleScrollID {
          state.recordVisiblePosition(target, renderer: rendererID)
          scroll(to: target, using: proxy)
        }
      }
      .onDisappear {
        state.invalidateRenderer(rendererID, revision: rendererRevision)
        if ownsState { state.cancelLoading() }
      }
    }
  }

  private func restorePosition(using proxy: ScrollViewProxy) {
    guard state.rendererRevision == rendererRevision, state.hasLoaded, !hasRestoredPosition else { return }
    let target = state.restoredPosition(in: viewModel.filteredSnapshots)
    restorationTarget = target
    scroll(to: target, using: proxy)
    if visiblePosition == target { hasRestoredPosition = true }
  }

  private func scroll(to target: TelemetryHistoryOverviewState.ScrollID, using proxy: ScrollViewProxy) {
    if case let .report(id) = target {
      proxy.scrollTo(id, anchor: .top)
    } else {
      proxy.scrollTo(target, anchor: .top)
    }
  }

  private func recordVisiblePosition(_ id: TelemetryHistoryOverviewState.ScrollID) {
    guard state.rendererRevision == rendererRevision else { return }
    visiblePosition = id
    if !hasRestoredPosition {
      guard id == restorationTarget else { return }
      hasRestoredPosition = true
    }
    state.recordVisiblePosition(id, renderer: rendererID)
  }

  private func position(_ id: TelemetryHistoryOverviewState.ScrollID) -> HistoryPositionModifier {
    HistoryPositionModifier(id: id, onVisible: recordVisiblePosition)
  }

  // MARK: - Empty State

  private var emptyState: some View {
    ContentUnavailableView(
      L10n.RemoteNodes.RemoteNodes.History.overviewTitle,
      systemImage: "chart.line.uptrend.xyaxis",
      description: Text(L10n.RemoteNodes.RemoteNodes.History.noSnapshotsMessage)
    )
  }

  // MARK: - Radio Section

  private func timeRangeSection(selection: Binding<HistoryTimeRange>) -> some View {
    Section {
      Picker(L10n.RemoteNodes.RemoteNodes.History.timeRange, selection: selection) {
        ForEach(HistoryTimeRange.allCases, id: \.self) { range in
          Text(range.label).tag(range)
        }
      }
      .pickerStyle(.segmented)
      .listRowBackground(Color.clear)
      .modifier(position(.timeRange))
    }
  }

  @ViewBuilder
  private func radioSection(filtered: [NodeStatusSnapshotDTO]) -> some View {
    let hasRadioData = viewModel.hasRadioData(in: filtered)

    if hasRadioData {
      Section {
        DisclosureGroup(isExpanded: Bindable(state).radioExpanded) {
          RadioMetricCharts(snapshots: filtered, ocvArray: viewModel.ocvArray) { chart in
            chart
          } packetSection: { group in
            Section(L10n.RemoteNodes.RemoteNodes.History.packets) {
              group
            }
          }
        } label: {
          Text(L10n.RemoteNodes.RemoteNodes.History.radioSection)
            .modifier(position(.radio))
        }
      }
      .themedRowBackground(theme)
    }
  }

  // MARK: - Sensors Section

  @ViewBuilder
  private func sensorsSection(filtered: [NodeStatusSnapshotDTO]) -> some View {
    if viewModel.hasTelemetryData(in: filtered) {
      let groups = ChannelGroup.groups(from: filtered)
      Section {
        DisclosureGroup(isExpanded: Bindable(state).sensorsExpanded) {
          if groups.count > 1 {
            ForEach(groups) { group in
              Section(L10n.RemoteNodes.RemoteNodes.Status.channel(group.channel)) {
                ForEach(group.charts) { chart in
                  chartView(for: chart)
                }
              }
            }
          } else if let group = groups.first {
            ForEach(group.charts) { chart in
              chartView(for: chart)
            }
          }
        } label: {
          Text(L10n.RemoteNodes.RemoteNodes.History.sensorsSection)
            .modifier(position(.sensors))
        }
      }
      .themedRowBackground(theme)
    } else if viewModel.hasSnapshots {
      Section {
        Text(L10n.RemoteNodes.RemoteNodes.History.sectionNotCaptured(
          L10n.RemoteNodes.RemoteNodes.History.sensorsSection
        ))
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .modifier(position(.sensors))
      }
      .themedRowBackground(theme)
    }
  }

  // MARK: - Neighbors Section

  @ViewBuilder
  private func neighborsSection(filtered: [NodeStatusSnapshotDTO]) -> some View {
    if viewModel.hasNeighborData(in: filtered) {
      let neighborCharts = buildNeighborCharts(from: filtered)
      Section {
        DisclosureGroup(isExpanded: Bindable(state).neighborsExpanded) {
          ForEach(neighborCharts, id: \.prefix) { neighbor in
            MetricChartView(
              title: neighbor.name,
              unit: "dB",
              dataPoints: neighbor.dataPoints,
              accentColor: .blue
            )
          }
        } label: {
          Text(L10n.RemoteNodes.RemoteNodes.History.neighborsSection)
            .modifier(position(.neighbors))
        }
      }
      .themedRowBackground(theme)
    } else if viewModel.hasSnapshots {
      Section {
        Text(L10n.RemoteNodes.RemoteNodes.History.sectionNotCaptured(
          L10n.RemoteNodes.RemoteNodes.History.neighborsSection
        ))
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .modifier(position(.neighbors))
      }
      .themedRowBackground(theme)
    }
  }

  // MARK: - Helpers

  private func chartView(for chart: TelemetryChartGroup) -> MetricChartView {
    MetricChartView(
      title: chart.title,
      unit: chart.sensorType?.localizedUnitSymbol ?? "",
      dataPoints: chart.dataPoints,
      accentColor: chart.sensorType?.chartColor ?? .cyan,
      yAxisDomain: chart.sensorType == .voltage ? viewModel.ocvArray.voltageChartDomain(dataPoints: chart.dataPoints) : nil
    )
  }

  private func buildNeighborCharts(from filtered: [NodeStatusSnapshotDTO]) -> [NeighborChart] {
    var charts: [Data: NeighborChart] = [:]
    for snapshot in filtered {
      for neighbor in snapshot.neighborSnapshots ?? [] {
        let point = MetricChartView.DataPoint(
          id: snapshot.id, date: snapshot.timestamp, value: neighbor.snr
        )
        if charts[neighbor.publicKeyPrefix] != nil {
          charts[neighbor.publicKeyPrefix]!.dataPoints.append(point)
        } else {
          let hexName = neighbor.publicKeyPrefix
            .map { String(format: "%02X", $0) }.joined()
          let resolvedName = viewModel.resolveNeighborName(prefix: neighbor.publicKeyPrefix) ?? hexName
          charts[neighbor.publicKeyPrefix] = NeighborChart(
            prefix: neighbor.publicKeyPrefix,
            name: resolvedName,
            dataPoints: [point]
          )
        }
      }
    }
    return charts.values.sorted { $0.name < $1.name }
  }

  private var retentionFooter: some View {
    Section {} footer: {
      Text(L10n.RemoteNodes.RemoteNodes.History.retentionNotice)
        .modifier(position(.retention))
    }
    .themedRowBackground(theme)
  }

  private struct HistoryPositionModifier: ViewModifier {
    let id: TelemetryHistoryOverviewState.ScrollID
    let onVisible: (TelemetryHistoryOverviewState.ScrollID) -> Void

    func body(content: Content) -> some View {
      content
        .id(id)
        .onGeometryChange(for: Bool.self) { proxy in
          let frame = proxy.frame(in: .named(TelemetryHistoryOverviewState.scrollCoordinateSpace))
          return frame.minY <= 0 && frame.maxY > 0
        } action: { isTopRow in
          if isTopRow { onVisible(id) }
        }
    }
  }
}

// MARK: - Private Types

private struct NeighborChart {
  let prefix: Data
  let name: String
  var dataPoints: [MetricChartView.DataPoint]
}
