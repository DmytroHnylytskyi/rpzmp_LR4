package ua.kpi.windpower

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.windpower.data.WindParkModel
import ua.kpi.windpower.data.geo.GeoDistanceCalculator
import ua.kpi.windpower.data.geo.GeoPointCoord
import ua.kpi.windpower.data.geo.MinPowerFilter
import ua.kpi.windpower.data.geo.StationSortOption
import ua.kpi.windpower.data.geo.WindGeoCatalog
import ua.kpi.windpower.data.geo.WindPlantStatus
import ua.kpi.windpower.data.geo.WindPlantType
import ua.kpi.windpower.data.stream.WindForecastTick
import ua.kpi.windpower.data.stream.WindSensorReading
import ua.kpi.windpower.data.stream.WindTelemetryFlowEngine

/**
 * Модульні тести функціональності ЛР №4 (ПЗ №13–16):
 * - холодні та гарячі потоки Flow, оператори `map`, `filter`, `combine`, `zip`, `retryWhen`, `catch`;
 * - розрахунок відстані за формулою Гаверсінуса та багаторівнева фільтрація/сортування ВЕС.
 */
class Lab4FlowAndGeoTest {

    // --- ПЗ №13: Kotlin Coroutines & Flow ---

    @Test
    fun flowEngine_sensorColdFlow_mapsRawSpeedViaWindParkModel() = runBlocking {
        val engine = WindTelemetryFlowEngine(sensorIntervalMs = 5L)
        val readings = engine.sensorColdFlow(
            allowFaults = false,
            speedGenerator = { tick -> if (tick == 1) 1.5 else 12.0 }
        ).take(2).toList()

        assertEquals(2, readings.size)
        val calmExpected = WindParkModel.resolveTelemetry(1.5)
        val ratedExpected = WindParkModel.resolveTelemetry(12.0)

        assertEquals(calmExpected.powerMw, readings[0].powerMw, 0.01)
        assertEquals(ratedExpected.powerMw, readings[1].powerMw, 0.01)
    }

    @Test
    fun flowEngine_filterAndRetryWhen_recoverFromTransientFaults() = runBlocking {
        val engine = WindTelemetryFlowEngine(
            sensorIntervalMs = 5L,
            forecastIntervalMs = 5L,
            retryDelayMs = 5L
        )
        val threshold = MutableStateFlow(5.0)
        var retryCount = 0
        val filteredDrops = mutableListOf<Double>()

        // Після 2 збоїв потік відновлюється; перший кадр (2.0 м/с) відкидається filter
        var emissionAttempt = 0
        val scriptedSensor = flow {
            emissionAttempt++
            if (emissionAttempt <= 2) {
                throw IllegalStateException("Тимчасовий збій Modbus #$emissionAttempt")
            }
            emit(WindSensorReading(1, 2.0, 0.0, 8, 7.0, "Штиль"))
            emit(WindSensorReading(2, 11.0, 12.2, 12, 8.0, "Робота MPPT"))
        }
        val fixedForecast = flow {
            emit(WindForecastTick(1, 10.5, 11.4, 90))
        }

        val frames = engine.observeCombinedStream(
            minWindThreshold = threshold,
            maxRetries = 3,
            sensorSource = scriptedSensor,
            forecastSource = fixedForecast,
            onFilteredOut = { speed, _ -> filteredDrops.add(speed) },
            onRetryAttempt = { _, _, _ -> retryCount++ }
        ).take(1).toList()

        assertEquals(2, retryCount)
        assertEquals(listOf(2.0), filteredDrops)
        assertEquals(1, frames.size)
        assertEquals(11.0, frames.first().sensor.rawSpeedMps, 0.01)
    }

    @Test
    fun flowEngine_catch_handlesTerminalFailureGracefully() = runBlocking {
        val engine = WindTelemetryFlowEngine(retryDelayMs = 2L)
        val threshold = MutableStateFlow(0.0)
        var retryAttempts = 0
        var capturedError: String? = null

        val alwaysFailingSensor = flow<WindSensorReading> {
            throw IllegalStateException("Критична відмова шлюзу SCADA")
        }
        val fixedForecast = flow {
            emit(WindForecastTick(1, 9.0, 9.5, 85))
        }

        val emitted = engine.observeCombinedStream(
            minWindThreshold = threshold,
            maxRetries = 2,
            sensorSource = alwaysFailingSensor,
            forecastSource = fixedForecast,
            onRetryAttempt = { _, _, _ -> retryAttempts++ },
            onFaultCaptured = { reason -> capturedError = reason }
        ).toList()

        assertEquals(0, emitted.size)
        assertEquals(2, retryAttempts)
        assertEquals("Критична відмова шлюзу SCADA", capturedError)
    }

    @Test
    fun flowEngine_catch_clearsRemainingFaultsBeforeNextCollection() = runBlocking {
        val engine = WindTelemetryFlowEngine(
            sensorIntervalMs = 5L,
            forecastIntervalMs = 5L,
            retryDelayMs = 2L
        )
        val threshold = MutableStateFlow(0.0)
        var capturedError: String? = null

        // Заплановано 5 збоїв (> maxRetries = 2): після вичерпання спроб залишок збоїв має очиститися
        engine.scheduleSimulatedFaults(5)
        engine.observeCombinedStream(
            minWindThreshold = threshold,
            maxRetries = 2,
            sensorSource = engine.sensorColdFlow(allowFaults = true) { 10.0 },
            forecastSource = engine.forecastColdFlow { 10.0 },
            onFaultCaptured = { capturedError = it }
        ).toList()

        assertTrue(capturedError != null)

        // Наступний збір після відновлення потоку повинен одразу видати кадр без залишкового збою
        val recoveredFrames = engine.observeCombinedStream(
            minWindThreshold = threshold,
            maxRetries = 2,
            sensorSource = engine.sensorColdFlow(allowFaults = true) { 11.5 },
            forecastSource = engine.forecastColdFlow { 11.0 }
        ).take(1).toList()

        assertEquals(1, recoveredFrames.size)
        assertEquals(11.5, recoveredFrames.first().sensor.rawSpeedMps, 0.01)
    }

    @Test
    fun flowEngine_combineAndZip_aggregateStreamsCorrectly() = runBlocking {
        val engine = WindTelemetryFlowEngine()
        val sensorSource = flow {
            emit(WindSensorReading(1, 10.0, 10.0, 10, 8.0, "MPPT"))
            emit(WindSensorReading(2, 12.0, 16.0, 14, 8.5, "Номінал"))
        }
        val forecastSource = flow {
            emit(WindForecastTick(1, 10.0, 8.0, 88))
            emit(WindForecastTick(2, 12.0, 16.0, 94))
        }

        val zippedDeltas = engine.zippedCalibrationDeltaFlow(sensorSource, forecastSource).toList()
        assertEquals(2, zippedDeltas.size)
        // Перша пара: (10 - 8) / 8 * 100% = +25.0%; друга: (16 - 16) / 16 = 0.0%
        assertEquals(25.0, zippedDeltas[0], 0.1)
        assertEquals(0.0, zippedDeltas[1], 0.1)
    }

    // --- ПЗ №14–16: Геолокація, фільтрація та сортування ---

    @Test
    fun geoDistanceCalculator_computesAccurateHaversineDistance() {
        // Київ (КПІ) -> Тилігульська ВЕС (~404 км по ортодромі)
        val distKm = GeoDistanceCalculator.distanceKm(50.4494, 30.4573, 46.8420, 31.1150)
        assertTrue("Очікувана відстань 395..415 км, отримано $distKm", distKm in 395.0..415.0)
        assertEquals(0.0, GeoDistanceCalculator.distanceKm(50.0, 30.0, 50.0, 30.0), 0.001)
    }

    @Test
    fun geoFilterAndSort_filtersByQueryTypeStatusAndMinPower() {
        val stations = WindGeoCatalog.initialStations

        val coastalActiveBig = GeoDistanceCalculator.filterAndSort(
            stations = stations,
            searchQuery = "Запорізька",
            typeFilter = WindPlantType.COASTAL,
            statusFilter = WindPlantStatus.ACTIVE,
            minPowerFilter = MinPowerFilter.MW_150,
            sortOption = StationSortOption.NAME_ASC,
            userLocation = null
        )
        assertEquals(1, coastalActiveBig.size)
        assertEquals("Приморська ВЕС", coastalActiveBig.first().name)

        // Порожній результат при суперечливому пошуку
        val empty = GeoDistanceCalculator.filterAndSort(
            stations = stations,
            searchQuery = "Неіснуюча_ВЕС",
            typeFilter = null,
            statusFilter = null,
            minPowerFilter = MinPowerFilter.ALL,
            sortOption = StationSortOption.NAME_ASC,
            userLocation = null
        )
        assertTrue(empty.isEmpty())
    }

    @Test
    fun geoFilterAndSort_sortsByGpsDistanceAndPowerAndDate() {
        val stations = WindGeoCatalog.initialStations
        val mykolaivPos = GeoPointCoord(latitude = 46.9750, longitude = 31.9946)

        val nearestFirst = GeoDistanceCalculator.filterAndSort(
            stations = stations,
            searchQuery = "",
            typeFilter = null,
            statusFilter = null,
            minPowerFilter = MinPowerFilter.ALL,
            sortOption = StationSortOption.DISTANCE_ASC,
            userLocation = mykolaivPos
        )
        // Тилігульська ВЕС розташована безпосередньо біля Миколаєва (~68 км)
        assertEquals("Тилігульська ВЕС", nearestFirst.first().name)

        val highestPowerFirst = GeoDistanceCalculator.filterAndSort(
            stations = stations,
            searchQuery = "",
            typeFilter = null,
            statusFilter = null,
            minPowerFilter = MinPowerFilter.ALL,
            sortOption = StationSortOption.POWER_DESC,
            userLocation = mykolaivPos
        )
        assertTrue(highestPowerFirst.first().capacityMw >= highestPowerFirst.last().capacityMw)
        assertEquals("Сиваська ВЕС", highestPowerFirst.first().name)
    }

    @Test
    fun windGeoCatalog_refreshStationTelemetry_updatesOutputAndRespectsMaintenance() {
        val activeStation = WindGeoCatalog.initialStations.first { it.status == WindPlantStatus.ACTIVE }
        val maintenanceStation = WindGeoCatalog.initialStations.first { it.status == WindPlantStatus.MAINTENANCE }

        val updatedActive = WindGeoCatalog.refreshStationTelemetry(activeStation, 1.2, "2026-11-23 22:30")
        assertEquals("2026-11-23 22:30", updatedActive.lastUpdated)
        assertTrue(updatedActive.currentPowerMw >= 0.0)

        val updatedMaint = WindGeoCatalog.refreshStationTelemetry(maintenanceStation, 5.0, "2026-11-23 22:30")
        assertEquals(WindPlantStatus.MAINTENANCE, updatedMaint.status)
    }
}
