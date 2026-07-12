import Foundation
@testable import MC1
import Testing

@Suite("NoiseFloorReading")
struct NoiseFloorReadingTests {
  @Test
  func `reading stores all values correctly`() {
    let timestamp = Date()
    let reading = NoiseFloorReading(
      id: UUID(),
      timestamp: timestamp,
      noiseFloor: -95,
      lastRSSI: -80,
      lastSNR: 7.5
    )

    #expect(reading.noiseFloor == -95)
    #expect(reading.lastRSSI == -80)
    #expect(reading.lastSNR == 7.5)
    #expect(reading.timestamp == timestamp)
  }
}

@Suite("NoiseFloorStatistics")
struct NoiseFloorStatisticsTests {
  @Test
  func `statistics calculates min/max/avg correctly`() {
    let stats = NoiseFloorStatistics(min: -110, max: -80, average: -95.5)

    #expect(stats.min == -110)
    #expect(stats.max == -80)
    #expect(stats.average == -95.5)
  }
}

@Suite("NoiseFloorQuality")
struct NoiseFloorQualityTests {
  @Test
  func `excellent for noise floor <= -100`() {
    #expect(NoiseFloorQuality.from(noiseFloor: -100) == .excellent)
    #expect(NoiseFloorQuality.from(noiseFloor: -110) == .excellent)
  }

  @Test
  func `good for noise floor <= -90`() {
    #expect(NoiseFloorQuality.from(noiseFloor: -90) == .good)
    #expect(NoiseFloorQuality.from(noiseFloor: -99) == .good)
  }

  @Test
  func `fair for noise floor <= -80`() {
    #expect(NoiseFloorQuality.from(noiseFloor: -80) == .fair)
    #expect(NoiseFloorQuality.from(noiseFloor: -89) == .fair)
  }

  @Test
  func `poor for noise floor > -80`() {
    #expect(NoiseFloorQuality.from(noiseFloor: -79) == .poor)
    #expect(NoiseFloorQuality.from(noiseFloor: -60) == .poor)
  }

  @Test
  func `label returns correct strings`() {
    #expect(NoiseFloorQuality.excellent.label == "Excellent")
    #expect(NoiseFloorQuality.good.label == "Good")
    #expect(NoiseFloorQuality.fair.label == "Fair")
    #expect(NoiseFloorQuality.poor.label == "Poor")
    #expect(NoiseFloorQuality.unknown.label == "Unknown")
  }

  @Test
  func `icon returns correct SF Symbols`() {
    #expect(NoiseFloorQuality.excellent.icon == "checkmark.circle.fill")
    #expect(NoiseFloorQuality.good.icon == "circle.fill")
    #expect(NoiseFloorQuality.fair.icon == "exclamationmark.circle.fill")
    #expect(NoiseFloorQuality.poor.icon == "xmark.circle.fill")
    #expect(NoiseFloorQuality.unknown.icon == "questionmark.circle")
  }
}

@Suite("NoiseFloorViewModel")
@MainActor
struct NoiseFloorViewModelTests {
  @Test
  func `initial state is empty`() {
    let viewModel = NoiseFloorViewModel()

    #expect(viewModel.currentReading == nil)
    #expect(viewModel.readings.isEmpty)
    #expect(viewModel.isPolling == false)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `statistics returns nil when no readings`() {
    let viewModel = NoiseFloorViewModel()

    #expect(viewModel.statistics == nil)
  }

  @Test
  func `statistics calculates correctly with readings`() {
    let viewModel = NoiseFloorViewModel()
    viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -100, lastRSSI: -80, lastSNR: 5))
    viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -90, lastRSSI: -80, lastSNR: 5))
    viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -95, lastRSSI: -80, lastSNR: 5))

    let stats = viewModel.statistics
    #expect(stats?.min == -100)
    #expect(stats?.max == -90)
    #expect(stats?.average == -95.0)
  }

  @Test
  func `qualityLevel returns unknown when no reading`() {
    let viewModel = NoiseFloorViewModel()

    #expect(viewModel.qualityLevel == .unknown)
  }

  @Test
  func `qualityLevel returns correct quality for current reading`() {
    let viewModel = NoiseFloorViewModel()
    viewModel.appendReading(NoiseFloorReading(
      id: UUID(),
      timestamp: .now,
      noiseFloor: -105,
      lastRSSI: -80,
      lastSNR: 5
    ))

    #expect(viewModel.qualityLevel == .excellent)
  }

  @Test
  func `appendReading adds to readings and updates current`() {
    let viewModel = NoiseFloorViewModel()
    let reading = NoiseFloorReading(
      id: UUID(),
      timestamp: .now,
      noiseFloor: -95,
      lastRSSI: -80,
      lastSNR: 5
    )

    viewModel.appendReading(reading)

    #expect(viewModel.readings.count == 1)
    #expect(viewModel.currentReading?.noiseFloor == -95)
  }

  @Test
  func `appendReading respects maxReadings limit`() {
    let viewModel = NoiseFloorViewModel()

    for i in 0..<250 {
      let reading = NoiseFloorReading(
        id: UUID(),
        timestamp: .now,
        noiseFloor: Int16(-100 + i),
        lastRSSI: -80,
        lastSNR: 5
      )
      viewModel.appendReading(reading)
    }

    #expect(viewModel.readings.count == 200)
  }

  @Test
  func `appendReading invalidates cached statistics`() {
    let viewModel = NoiseFloorViewModel()
    viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -100, lastRSSI: -80, lastSNR: 5))

    let stats1 = viewModel.statistics
    #expect(stats1?.min == -100)
    #expect(stats1?.max == -100)

    viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -90, lastRSSI: -80, lastSNR: 5))

    let stats2 = viewModel.statistics
    #expect(stats2?.min == -100)
    #expect(stats2?.max == -90)
  }

  @Test
  func `stopPolling cancels task and sets isPolling false`() {
    let viewModel = NoiseFloorViewModel()
    viewModel.isPolling = true

    viewModel.stopPolling()

    #expect(viewModel.isPolling == false)
  }
}
