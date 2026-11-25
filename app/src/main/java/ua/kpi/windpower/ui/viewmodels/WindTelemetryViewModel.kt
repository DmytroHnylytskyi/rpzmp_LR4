package ua.kpi.windpower.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ua.kpi.windpower.data.WindParkModel
import ua.kpi.windpower.data.stream.CombinedTelemetryFrame
import ua.kpi.windpower.data.stream.StreamAlertEvent
import ua.kpi.windpower.data.stream.StreamHealthState
import ua.kpi.windpower.data.stream.WindSensorReading
import ua.kpi.windpower.data.stream.WindTelemetryFlowEngine
import java.util.Locale

/**
 * ViewModel для оперативного та потокового SCADA-моніторингу ВЕС (ЛР №2 + ПЗ №13).
 *
 * Поєднує:
 * - ручне асинхронне опитування польових контролерів ([refreshTelemetry]) зі збереженням останніх показників;
 * - безперервні потоки Kotlin Flow ([WindTelemetryFlowEngine]) з операторами
 *   `map`, `filter`, `debounce`, `combine`, `zip`, `retryWhen`, `catch`;
 * - «гарячі» потоки [SharedFlow] (`alertBus` та `hotSensorFeed` через `shareIn`).
 */
@OptIn(FlowPreview::class)
class WindTelemetryViewModel : ViewModel() {

    private val flowEngine = WindTelemetryFlowEngine()

    // --- Стани ручного SCADA-опитування (спадковість ЛР №2) ---

    private val _power = MutableStateFlow(WindParkModel.initialTelemetry().powerMw)
    val power: StateFlow<Double> = _power.asStateFlow()

    private val _windSpeed = MutableStateFlow(WindParkModel.DEFAULT_WIND_SPEED)
    val windSpeed: StateFlow<Double> = _windSpeed.asStateFlow()

    private val _status = MutableStateFlow(WindParkModel.initialTelemetry().status)
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Активна помилка опитування давачів; null — зв'язок справний. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // --- Реактивні потоки телеметрії у реальному часі (ПЗ №13) ---

    private val _rawMinWindInput = MutableStateFlow(0.0)
    val rawMinWindInput: StateFlow<Double> = _rawMinWindInput.asStateFlow()

    private val _debouncedMinWindMps = MutableStateFlow(0.0)
    val debouncedMinWindMps: StateFlow<Double> = _debouncedMinWindMps.asStateFlow()

    private val _isStreaming = MutableStateFlow(true)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _combinedFrame = MutableStateFlow<CombinedTelemetryFrame?>(null)
    val combinedFrame: StateFlow<CombinedTelemetryFrame?> = _combinedFrame.asStateFlow()

    private val _zippedCalibrationDelta = MutableStateFlow<Double?>(null)
    val zippedCalibrationDelta: StateFlow<Double?> = _zippedCalibrationDelta.asStateFlow()

    private val _streamHealth = MutableStateFlow<StreamHealthState>(StreamHealthState.Streaming)
    val streamHealth: StateFlow<StreamHealthState> = _streamHealth.asStateFlow()

    private val _recentFrames = MutableStateFlow<List<WindSensorReading>>(emptyList())
    val recentFrames: StateFlow<List<WindSensorReading>> = _recentFrames.asStateFlow()

    private val _alertHistory = MutableStateFlow<List<StreamAlertEvent>>(emptyList())
    val alertHistory: StateFlow<List<StreamAlertEvent>> = _alertHistory.asStateFlow()

    /** «Гарячий» широкомовний потік підписки на покази сенсора (shareIn). */
    val hotSensorFeed: SharedFlow<WindSensorReading> = flowEngine.hotSharedSensorFlow(viewModelScope)

    private var pipelinesStarted = false
    private var combinedStreamJob: Job? = null
    private var zipCalibrationJob: Job? = null

    /**
     * Запускає фонові реактивні конвеєри (`debounce`, шину `alertBus` та потоки телеметрії)
     * при першому відображенні екрану, не викликаючи `Dispatchers.Main` у конструкторі.
     */
    fun ensureStreamingStarted() {
        if (pipelinesStarted) return
        pipelinesStarted = true

        // Згладжування (debounce) зміни порогу фільтрації вітрового потоку
        viewModelScope.launch {
            _rawMinWindInput
                .debounce(250L)
                .distinctUntilChanged()
                .drop(1)
                .collect { settled ->
                    _debouncedMinWindMps.value = settled
                    pushAlert(
                        StreamAlertEvent(
                            tick = _recentFrames.value.firstOrNull()?.tick ?: 0,
                            level = "ФІЛЬТР",
                            message = "Оновлено поріг фільтрації: ≥ ${String.format(Locale.US, "%.1f", settled)} м/с"
                        )
                    )
                }
        }

        // Підписка на «гарячу» шину критичних подій SCADA
        viewModelScope.launch {
            flowEngine.alertBus.collect { event ->
                pushAlert(event)
            }
        }

        startStreaming()
    }

    /**
     * Запускає потоковий збір даних із комбінуванням джерел (`combine` та `zip`).
     */
    fun startStreaming() {
        combinedStreamJob?.cancel()
        zipCalibrationJob?.cancel()
        _isStreaming.value = true
        _streamHealth.value = StreamHealthState.Streaming

        combinedStreamJob = viewModelScope.launch {
            flowEngine.observeCombinedStream(
                minWindThreshold = _debouncedMinWindMps,
                maxRetries = 3,
                onFilteredOut = { speed, threshold ->
                    _streamHealth.value = StreamHealthState.FilteredOut(speed, threshold)
                },
                onRetryAttempt = { attempt, maxAttempts, reason ->
                    _streamHealth.value = StreamHealthState.Retrying(attempt, maxAttempts, reason)
                },
                onFaultCaptured = { reason ->
                    zipCalibrationJob?.cancel()
                    zipCalibrationJob = null
                    _streamHealth.value = StreamHealthState.Fault(reason)
                    _isStreaming.value = false
                }
            ).collect { frame ->
                _combinedFrame.value = frame
                _streamHealth.value = StreamHealthState.Streaming
                _recentFrames.value = (listOf(frame.sensor) + _recentFrames.value).take(8)
            }
        }

        // Паралельне попарне калібрування давача та еталонної прогнозної моделі через оператор zip
        zipCalibrationJob = viewModelScope.launch {
            flowEngine.zippedCalibrationDeltaFlow(
                sensorReadings = hotSensorFeed,
                referenceReadings = flowEngine.forecastColdFlow()
            )
                .catch { /* Помилка калібрування не зупиняє головний потік */ }
                .collect { delta ->
                    _zippedCalibrationDelta.value = delta
                }
        }
    }

    /**
     * Призупиняє активну підписку на холодні та комбіновані потоки даних.
     */
    fun pauseStreaming() {
        combinedStreamJob?.cancel()
        zipCalibrationJob?.cancel()
        combinedStreamJob = null
        zipCalibrationJob = null
        flowEngine.clearSimulatedFaults()
        _isStreaming.value = false
        _streamHealth.value = StreamHealthState.Paused
    }

    /**
     * Перемикає стан потоку «Пауза / Активний».
     */
    fun toggleStreaming() {
        if (_isStreaming.value) pauseStreaming() else startStreaming()
    }

    /**
     * Задає мінімальний поріг швидкості вітру (м/с) для оператора `filter` (через `debounce`).
     */
    fun updateMinWindFilter(thresholdMps: Double) {
        _rawMinWindInput.value = thresholdMps.coerceIn(0.0, 14.0)
    }

    /**
     * Ініціює серію штучних збоїв давача для перевірки `retryWhen` (2 збої → відновлення)
     * або термінальної обробки `catch` (5 збоїв > maxRetries = 3).
     */
    fun triggerSimulatedSensorFault(consecutiveFailures: Int) {
        flowEngine.scheduleSimulatedFaults(consecutiveFailures)
        if (!_isStreaming.value) {
            startStreaming()
        }
    }

    /**
     * Асинхронно опитує промислові давачі вітропарку через корутину з імітацією
     * затримки мережі (модуль ручного SCADA-опитування з ЛР №2).
     */
    fun refreshTelemetry() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true

            val isRecoveryAttempt = _error.value != null
            _error.value = null
            delay(1000)

            if (!isRecoveryAttempt && (1..100).random() <= SCADA_FAILURE_CHANCE_PERCENT) {
                _error.value =
                    "Тайм-аут опитування давача анемометра (помилка $SCADA_TIMEOUT_CODE). Повторіть запит."
                _isLoading.value = false
                return@launch
            }

            val newSpeed = ((10..280).random() / 10.0)
            val state = WindParkModel.resolveTelemetry(newSpeed)
            _windSpeed.value = state.windSpeed
            _power.value = state.powerMw
            _status.value = state.status

            _isLoading.value = false
        }
    }

    private fun pushAlert(event: StreamAlertEvent) {
        _alertHistory.value = (listOf(event) + _alertHistory.value).take(6)
    }

    private companion object {
        const val SCADA_TIMEOUT_CODE = 503
        const val SCADA_FAILURE_CHANCE_PERCENT = 10
    }
}
