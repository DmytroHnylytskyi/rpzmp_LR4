package ua.kpi.windpower.data.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.zip
import ua.kpi.windpower.data.WindParkModel
import ua.kpi.windpower.data.WindPowerCalculator
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Окремий телеметричний кадр, сформований із холодного потоку давача вітропарку
 * після перетворення оператором [map].
 */
data class WindSensorReading(
    val tick: Int,
    val rawSpeedMps: Double,
    val powerMw: Double,
    val turbulencePercent: Int,
    val temperatureC: Double,
    val status: String
)

/**
 * Кадр прогнозної NWP-моделі генерації вітрової електростанції.
 */
data class WindForecastTick(
    val tick: Int,
    val expectedWindMps: Double,
    val expectedPowerMw: Double,
    val confidencePercent: Int
)

/**
 * Комбінований зріз телеметрії, отриманий через оператор [combine] для двох
 * незалежних потоків (давач SCADA + прогнозна модель NWP) та калібрування через [zip].
 */
data class CombinedTelemetryFrame(
    val sensor: WindSensorReading,
    val forecast: WindForecastTick,
    val combinedAveragePowerMw: Double,
    val powerDeviationMw: Double,
    val calibrationDeltaPercent: Double
)

/**
 * Стан життєвого циклу реактивного потоку телеметрії (ПЗ №13).
 */
sealed interface StreamHealthState {
    data object Paused : StreamHealthState
    data object Streaming : StreamHealthState
    data class FilteredOut(val speedMps: Double, val thresholdMps: Double) : StreamHealthState
    data class Retrying(val attempt: Int, val maxAttempts: Int, val reason: String) : StreamHealthState
    data class Fault(val message: String) : StreamHealthState
}

/**
 * Подія «гарячої» шини SCADA (`SharedFlow`) про критичні режими чи відновлення каналу.
 */
data class StreamAlertEvent(
    val tick: Int,
    val level: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Асинхронний двигун потокової телеметрії ВЕС на базі Kotlin Coroutines та Flow (ПЗ №13).
 *
 * Реалізує:
 * - «холодні» потоки (`flow { ... }`) для давачів вітру та NWP-прогнозу;
 * - перетворення даних оператором [map] через фізичну модель парку [WindParkModel];
 * - фільтрацію порогу генерації через [filter];
 * - автоматичне відновлення при збоях через [retryWhen] та безпечне перехоплення [catch];
 * - об'єднання асинхронних потоків операторами [combine] та [zip];
 * - переведення у «гарячий» широкомовний потік [SharedFlow] (`shareIn` та `MutableSharedFlow`).
 */
class WindTelemetryFlowEngine(
    private val sensorIntervalMs: Long = 1000L,
    private val forecastIntervalMs: Long = 1500L,
    private val retryDelayMs: Long = 800L
) {

    @Volatile
    private var simulatedFaultsRemaining: Int = 0

    private val _alertBus = MutableSharedFlow<StreamAlertEvent>(extraBufferCapacity = 16)
    val alertBus: SharedFlow<StreamAlertEvent> = _alertBus

    /**
     * Планує [count] послідовних штучних збоїв давача для демонстрації механізмів `retryWhen` та `catch`.
     */
    fun scheduleSimulatedFaults(count: Int) {
        simulatedFaultsRemaining = count.coerceAtLeast(0)
    }

    /**
     * Скидає лічильник штучно запланованих збоїв давача (наприклад, після перехоплення `catch` або паузи).
     */
    fun clearSimulatedFaults() {
        simulatedFaultsRemaining = 0
    }

    /**
     * «Холодний» потік показань польового анемометра SCADA.
     * Кожний підписник отримує незалежний генератор кадрів, поки не застосовано `shareIn`.
     */
    fun sensorColdFlow(
        allowFaults: Boolean = true,
        speedGenerator: (tick: Int) -> Double = { tick ->
            val wave = kotlin.math.sin(tick * 0.45) * 4.5 + 10.2 + Random.nextDouble(-1.8, 2.2)
            round1(wave.coerceIn(0.8, 27.5))
        }
    ): Flow<WindSensorReading> = flow {
        var tick = 0
        while (true) {
            delay(sensorIntervalMs)
            tick++
            if (allowFaults && simulatedFaultsRemaining > 0) {
                simulatedFaultsRemaining--
                throw IllegalStateException("Тайм-аут Modbus-давача на кадрі #$tick")
            }
            emit(tick to speedGenerator(tick))
        }
    }.map { (tick, rawSpeed) ->
        // Оператор map: розрахунок активної потужності парку та статусу за фізичною моделлю
        val telemetry = WindParkModel.resolveTelemetry(rawSpeed)
        val turbulence = (8 + (rawSpeed * 0.65).toInt()).coerceIn(5, 28)
        val temperature = round1(7.5 + kotlin.math.cos(tick * 0.3) * 2.5)
        WindSensorReading(
            tick = tick,
            rawSpeedMps = round1(telemetry.windSpeed),
            powerMw = round2(telemetry.powerMw),
            turbulencePercent = turbulence,
            temperatureC = temperature,
            status = telemetry.status
        )
    }

    /**
     * «Холодний» потік прогнозної NWP-моделі генерації ВЕС (оновлюється незалежним циклом).
     */
    fun forecastColdFlow(
        speedGenerator: (tick: Int) -> Double = { tick ->
            val trend = kotlin.math.sin(tick * 0.35 + 0.4) * 3.8 + 10.5 + Random.nextDouble(-0.9, 1.1)
            round1(trend.coerceIn(1.5, 24.5))
        }
    ): Flow<WindForecastTick> = flow {
        var tick = 0
        while (true) {
            delay(forecastIntervalMs)
            tick++
            emit(tick to speedGenerator(tick))
        }
    }.map { (tick, expectedSpeed) ->
        val projected = WindParkModel.resolveTelemetry(expectedSpeed)
        val confidence = (92 - abs(expectedSpeed - 11.0).toInt()).coerceIn(68, 98)
        WindForecastTick(
            tick = tick,
            expectedWindMps = round1(projected.windSpeed),
            expectedPowerMw = round2(projected.powerMw),
            confidencePercent = confidence
        )
    }

    /**
     * Створює «гарячий» широкомовний потік ([SharedFlow]) з використанням [shareIn]
     * для спільного прослуховування кількома споживачами без повторного запуску опитування.
     */
    fun hotSharedSensorFlow(scope: CoroutineScope): SharedFlow<WindSensorReading> =
        sensorColdFlow(allowFaults = false)
            .shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000L),
                replay = 1
            )

    /**
     * Попарно синхронізує потік давача та еталонних показників за допомогою оператора [zip]
     * для розрахунку миттєвої відносної похибки калібрування (%).
     */
    fun zippedCalibrationDeltaFlow(
        sensorReadings: Flow<WindSensorReading>,
        referenceReadings: Flow<WindForecastTick>
    ): Flow<Double> = sensorReadings.zip(referenceReadings) { sensor, ref ->
        val base = maxOf(ref.expectedPowerMw, 1.0)
        round1(((sensor.powerMw - ref.expectedPowerMw) / base) * 100.0)
    }

    /**
     * Основний конвеєр потокової обробки даних ПЗ №13:
     * об'єднує давач і прогноз ([combine]), фільтрує шум нижче порогу ([filter]),
     * виконує автоповтор при збоях ([retryWhen]) і перехоплює термінальну помилку ([catch]).
     */
    fun observeCombinedStream(
        minWindThreshold: StateFlow<Double>,
        maxRetries: Int = 3,
        sensorSource: Flow<WindSensorReading> = sensorColdFlow(),
        forecastSource: Flow<WindForecastTick> = forecastColdFlow(),
        onFilteredOut: (speedMps: Double, thresholdMps: Double) -> Unit = { _, _ -> },
        onRetryAttempt: (attempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> },
        onFaultCaptured: (reason: String) -> Unit = {}
    ): Flow<CombinedTelemetryFrame> {
        val calibratedSensor = sensorSource
            .filter { reading ->
                val passes = reading.rawSpeedMps >= minWindThreshold.value
                if (!passes) {
                    onFilteredOut(reading.rawSpeedMps, minWindThreshold.value)
                }
                passes
            }

        return combine(calibratedSensor, forecastSource) { sensor, forecast ->
            val avgPower = round2((sensor.powerMw + forecast.expectedPowerMw) / 2.0)
            val deviation = round2(sensor.powerMw - forecast.expectedPowerMw)
            val calDelta = if (forecast.expectedPowerMw <= 0.1 && sensor.powerMw <= 0.1) {
                0.0
            } else {
                val base = maxOf(forecast.expectedPowerMw, 1.0)
                round1((deviation / base) * 100.0)
            }
            if (sensor.rawSpeedMps > WindPowerCalculator.CUT_OUT_MPS) {
                _alertBus.tryEmit(
                    StreamAlertEvent(
                        tick = sensor.tick,
                        level = "ШТОРМ",
                        message = "Швидкість ${sensor.rawSpeedMps} м/с — захист Cut-out"
                    )
                )
            }
            CombinedTelemetryFrame(
                sensor = sensor,
                forecast = forecast,
                combinedAveragePowerMw = avgPower,
                powerDeviationMw = deviation,
                calibrationDeltaPercent = calDelta
            )
        }
            .retryWhen { cause, attempt ->
                if (attempt < maxRetries) {
                    val attemptNo = attempt.toInt() + 1
                    val reason = cause.message ?: "Збій давача SCADA"
                    _alertBus.tryEmit(
                        StreamAlertEvent(
                            tick = attemptNo,
                            level = "RETRY",
                            message = "Повтор підключення $attemptNo/$maxRetries: $reason"
                        )
                    )
                    onRetryAttempt(attemptNo, maxRetries, reason)
                    delay(retryDelayMs)
                    true
                } else {
                    false
                }
            }
            .catch { cause ->
                clearSimulatedFaults()
                val reason = cause.message ?: "Невідома помилка телеметричного потоку"
                _alertBus.tryEmit(
                    StreamAlertEvent(
                        tick = 0,
                        level = "ВІДМОВА",
                        message = reason
                    )
                )
                onFaultCaptured(reason)
            }
    }

    private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
