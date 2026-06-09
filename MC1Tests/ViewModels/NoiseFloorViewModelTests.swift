import Testing
import Foundation
@testable import MC1

@Suite("NoiseFloorReading")
struct NoiseFloorReadingTests {

    @Test("reading stores all values correctly")
    func readingStoresValues() {
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

    @Test("statistics calculates min/max/avg correctly")
    func statisticsCalculatesCorrectly() {
        let stats = NoiseFloorStatistics(min: -110, max: -80, average: -95.5)

        #expect(stats.min == -110)
        #expect(stats.max == -80)
        #expect(stats.average == -95.5)
    }
}

@Suite("NoiseFloorQuality")
struct NoiseFloorQualityTests {

    @Test("excellent for noise floor <= -100")
    func excellentThreshold() {
        #expect(NoiseFloorQuality.from(noiseFloor: -100) == .excellent)
        #expect(NoiseFloorQuality.from(noiseFloor: -110) == .excellent)
    }

    @Test("good for noise floor <= -90")
    func goodThreshold() {
        #expect(NoiseFloorQuality.from(noiseFloor: -90) == .good)
        #expect(NoiseFloorQuality.from(noiseFloor: -99) == .good)
    }

    @Test("fair for noise floor <= -80")
    func fairThreshold() {
        #expect(NoiseFloorQuality.from(noiseFloor: -80) == .fair)
        #expect(NoiseFloorQuality.from(noiseFloor: -89) == .fair)
    }

    @Test("poor for noise floor > -80")
    func poorThreshold() {
        #expect(NoiseFloorQuality.from(noiseFloor: -79) == .poor)
        #expect(NoiseFloorQuality.from(noiseFloor: -60) == .poor)
    }

    @Test("label returns correct strings")
    func labelReturnsCorrectStrings() {
        #expect(NoiseFloorQuality.excellent.label == "Excellent")
        #expect(NoiseFloorQuality.good.label == "Good")
        #expect(NoiseFloorQuality.fair.label == "Fair")
        #expect(NoiseFloorQuality.poor.label == "Poor")
        #expect(NoiseFloorQuality.unknown.label == "Unknown")
    }

    @Test("icon returns correct SF Symbols")
    func iconReturnsCorrectSymbols() {
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

    @Test("initial state is empty")
    func initialStateIsEmpty() {
        let viewModel = NoiseFloorViewModel()

        #expect(viewModel.currentReading == nil)
        #expect(viewModel.readings.isEmpty)
        #expect(viewModel.isPolling == false)
        #expect(viewModel.errorMessage == nil)
    }

    @Test("statistics returns nil when no readings")
    func statisticsReturnsNilWhenEmpty() {
        let viewModel = NoiseFloorViewModel()

        #expect(viewModel.statistics == nil)
    }

    @Test("statistics calculates correctly with readings")
    func statisticsCalculatesWithReadings() {
        let viewModel = NoiseFloorViewModel()
        viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -100, lastRSSI: -80, lastSNR: 5))
        viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -90, lastRSSI: -80, lastSNR: 5))
        viewModel.appendReading(NoiseFloorReading(id: UUID(), timestamp: .now, noiseFloor: -95, lastRSSI: -80, lastSNR: 5))

        let stats = viewModel.statistics
        #expect(stats?.min == -100)
        #expect(stats?.max == -90)
        #expect(stats?.average == -95.0)
    }

    @Test("qualityLevel returns unknown when no reading")
    func qualityLevelReturnsUnknownWhenNoReading() {
        let viewModel = NoiseFloorViewModel()

        #expect(viewModel.qualityLevel == .unknown)
    }

    @Test("qualityLevel returns correct quality for current reading")
    func qualityLevelReturnsCorrectQuality() {
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

    @Test("appendReading adds to readings and updates current")
    func appendReadingAddsToReadings() {
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

    @Test("appendReading respects maxReadings limit")
    func appendReadingRespectsLimit() {
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

    @Test("appendReading invalidates cached statistics")
    func appendReadingInvalidatesCache() {
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

    @Test("stopPolling cancels task and sets isPolling false")
    func stopPollingCancelsTask() {
        let viewModel = NoiseFloorViewModel()
        viewModel.isPolling = true

        viewModel.stopPolling()

        #expect(viewModel.isPolling == false)
    }
}
