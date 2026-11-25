package ua.kpi.windpower.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.windpower.data.datasource.ForecastSortMode
import ua.kpi.windpower.data.network.ErrorLogEntry
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.remote.dto.WindForecastDto
import ua.kpi.windpower.data.remote.dto.WindForecastStatsDto
import ua.kpi.windpower.data.repository.DataOrigin
import ua.kpi.windpower.data.repository.ForecastAnalyticsSummary
import ua.kpi.windpower.di.AppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Одноразова подія для відображення Snackbar у Compose UI.
 */
data class UiMessageEvent(
    val message: String,
    val isError: Boolean = false,
    val id: Long = System.nanoTime()
)

/**
 * Стан екрану прогнозування потужності ВЕС, REST API та мережевої діагностики (ПЗ №9, №10, №12).
 */
data class WindForecastUiState(
    val stations: List<RemoteWindStationDto> = emptyList(),
    val selectedStationId: Int? = null,
    val forecasts: List<WindForecastDto> = emptyList(),
    val stationStats: WindForecastStatsDto? = null,
    val summary: ForecastAnalyticsSummary = ForecastAnalyticsSummary(),
    val dataOrigin: DataOrigin = DataOrigin.REMOTE_SYNCED,
    val lastSyncTime: String = "—",
    val searchQuery: String = "",
    val sortMode: ForecastSortMode = ForecastSortMode.DATE_ASC,
    val isLoading: Boolean = false,
    val retryBanner: String? = null,
    val todayForecast: WindForecastDto? = null,
    val isOfflineSimulated: Boolean = false,
    val useRealServer: Boolean = false,
    val errorLogs: List<ErrorLogEntry> = emptyList(),
    val snackbarEvent: UiMessageEvent? = null
)

/**
 * ViewModel екрану прогнозування вітрової генерації.
 *
 * Інтегрує:
 * - [ua.kpi.windpower.data.repository.WindForecastRepository] (Local + Remote DataSource, TTL-кеш, CRUD, агрегація);
 * - централізований [ua.kpi.windpower.data.network.NetworkErrorHandler] + [CoroutineExceptionHandler];
 * - автоматичні повторні спроби (retry) при тимчасових збоях та журнал `error_log.txt`.
 */
class WindForecastViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val repository = container.forecastRepository
    private val stationRepository = container.windStationRepository
    private val errorHandler = container.errorHandler
    private val errorLogSink = container.errorLogSink
    private val networkMonitor = container.networkMonitor
    private val mockBackend = container.mockBackend

    private val _uiState = MutableStateFlow(
        WindForecastUiState(
            isOfflineSimulated = networkMonitor.simulatedOffline,
            useRealServer = !mockBackend.enabled,
            errorLogs = errorLogSink.readAll()
        )
    )
    val uiState: StateFlow<WindForecastUiState> = _uiState.asStateFlow()

    /**
     * Глобальний обробник неперехоплених винятків корутин (ПЗ №12).
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val message = errorHandler.handleException(throwable)
        _uiState.update {
            it.copy(
                isLoading = false,
                retryBanner = null,
                errorLogs = errorLogSink.readAll(),
                snackbarEvent = UiMessageEvent(message = message, isError = true)
            )
        }
    }

    init {
        viewModelScope.launch(coroutineExceptionHandler) {
            stationRepository.observeAllStations().collect { roomStations ->
                val dtos = roomStations.map { st ->
                    RemoteWindStationDto(
                        id = st.id,
                        name = st.name,
                        location = st.location,
                        capacityMw = st.installedCapacityMw,
                        turbinesCount = st.turbinesCount
                    )
                }
                _uiState.update { state ->
                    val selectedExists = state.selectedStationId == null ||
                        dtos.any { it.id == state.selectedStationId }
                    state.copy(
                        stations = dtos,
                        selectedStationId = if (selectedExists) state.selectedStationId else null,
                        stationStats = if (selectedExists) state.stationStats else null
                    )
                }
            }
        }
        loadInitialData(forceRefresh = false)
    }

    fun loadInitialData(forceRefresh: Boolean = false) {
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true, retryBanner = null) }

            val stationsResult = repository.getStations { attempt, max, reason ->
                _uiState.update {
                    it.copy(retryBanner = "Повтор запиту станцій ($attempt/$max): $reason")
                }
            }
            stationsResult.onSuccess { list ->
                _uiState.update { it.copy(stations = list) }
            }

            fetchForecastsInternal(
                stationId = _uiState.value.selectedStationId,
                forceRefresh = forceRefresh
            )
        }
    }

    fun selectStation(stationId: Int?) {
        _uiState.update { it.copy(selectedStationId = stationId, stationStats = null) }
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true, retryBanner = null) }
            fetchForecastsInternal(stationId = stationId, forceRefresh = false)
        }
    }

    fun refreshFromServer() {
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true, retryBanner = null) }
            if (_uiState.value.stations.isEmpty()) {
                repository.getStations().onSuccess { list ->
                    _uiState.update { it.copy(stations = list) }
                }
            }
            fetchForecastsInternal(
                stationId = _uiState.value.selectedStationId,
                forceRefresh = true
            )
        }
    }

    private suspend fun fetchForecastsInternal(
        stationId: Int?,
        forceRefresh: Boolean,
        silentSyncSnackbar: Boolean = false
    ) {
        val result = repository.getForecasts(
            stationId = stationId,
            forceRefresh = forceRefresh,
            onRetryAttempt = { attempt, max, reason ->
                _uiState.update {
                    it.copy(retryBanner = "Повтор запиту прогнозів ($attempt/$max): $reason")
                }
            }
        )

        val current = _uiState.value
        result.onSuccess { loadResult ->
            val stationCapacityMw = current.stations.firstOrNull { it.id == stationId }?.capacityMw ?: 0.0
            val statsDto = if (stationId != null) {
                val shouldSkipRemoteStats =
                    loadResult.origin == DataOrigin.OFFLINE_CACHE_FALLBACK || !networkMonitor.isInternetAvailable()
                if (shouldSkipRemoteStats) {
                    repository.computeStationStatsFromLocal(stationId, stationCapacityMw)
                } else {
                    repository.getStationStats(stationId).getOrElse {
                        repository.computeStationStatsFromLocal(stationId, stationCapacityMw)
                    }
                }
            } else {
                null
            }

            val displayed = repository.queryLocal(
                stationId = stationId,
                query = current.searchQuery,
                sortMode = current.sortMode
            )
            val originNote = loadResult.warningMessage ?: when {
                silentSyncSnackbar -> null
                loadResult.origin == DataOrigin.REMOTE_SYNCED ->
                    "✓ Дані оновлено з REST API (${loadResult.items.size} записів)"
                loadResult.origin == DataOrigin.CACHE_FRESH -> null
                else -> loadResult.warningMessage
            }
            _uiState.update {
                it.copy(
                    forecasts = displayed,
                    summary = loadResult.summary,
                    stationStats = statsDto,
                    dataOrigin = loadResult.origin,
                    lastSyncTime = if (loadResult.origin == DataOrigin.REMOTE_SYNCED) formatNow() else it.lastSyncTime,
                    isLoading = false,
                    retryBanner = null,
                    errorLogs = errorLogSink.readAll(),
                    snackbarEvent = originNote?.let { msg ->
                        UiMessageEvent(
                            message = msg,
                            isError = loadResult.origin == DataOrigin.OFFLINE_CACHE_FALLBACK
                        )
                    }
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    isLoading = false,
                    retryBanner = null,
                    errorLogs = errorLogSink.readAll(),
                    snackbarEvent = UiMessageEvent(
                        message = err.message ?: "Помилка завантаження прогнозів",
                        isError = true
                    )
                )
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyLocalFilterAndSort()
    }

    fun onSortModeChange(mode: ForecastSortMode) {
        _uiState.update { it.copy(sortMode = mode) }
        applyLocalFilterAndSort()
    }

    private fun applyLocalFilterAndSort() {
        viewModelScope.launch(coroutineExceptionHandler) {
            val state = _uiState.value
            val filtered = repository.queryLocal(
                stationId = state.selectedStationId,
                query = state.searchQuery,
                sortMode = state.sortMode
            )
            _uiState.update {
                it.copy(
                    forecasts = filtered,
                    summary = repository.computeSummary(filtered)
                )
            }
        }
    }

    fun addForecast(
        stationId: Int,
        date: String,
        windSpeedMps: Double,
        predictedPowerMw: Double,
        temperatureC: Double,
        turbulencePercent: Int,
        notes: String?
    ) {
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true) }
            val dto = WindForecastDto(
                id = 0,
                stationId = stationId,
                date = date.trim(),
                windSpeedMps = windSpeedMps,
                predictedPowerMw = predictedPowerMw,
                temperatureC = temperatureC,
                turbulencePercent = turbulencePercent,
                notes = notes?.trim()?.takeIf { it.isNotEmpty() }
            )
            repository.addForecast(dto)
                .onSuccess {
                    fetchForecastsInternal(
                        stationId = _uiState.value.selectedStationId,
                        forceRefresh = true,
                        silentSyncSnackbar = true
                    )
                    emitMessage("✓ Прогноз на ${it.date} додано через REST API", isError = false)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorLogs = errorLogSink.readAll(),
                            snackbarEvent = UiMessageEvent(
                                message = err.message ?: "Не вдалося додати прогноз",
                                isError = true
                            )
                        )
                    }
                }
        }
    }

    fun updateForecast(forecast: WindForecastDto) {
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true) }
            repository.updateForecast(forecast)
                .onSuccess {
                    fetchForecastsInternal(
                        stationId = _uiState.value.selectedStationId,
                        forceRefresh = true,
                        silentSyncSnackbar = true
                    )
                    emitMessage("✓ Прогноз #${it.id} оновлено", isError = false)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorLogs = errorLogSink.readAll(),
                            snackbarEvent = UiMessageEvent(
                                message = err.message ?: "Не вдалося оновити прогноз",
                                isError = true
                            )
                        )
                    }
                }
        }
    }

    fun deleteForecast(id: Int) {
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true) }
            repository.deleteForecast(id)
                .onSuccess {
                    fetchForecastsInternal(
                        stationId = _uiState.value.selectedStationId,
                        forceRefresh = true,
                        silentSyncSnackbar = true
                    )
                    emitMessage("✓ Прогноз #$id видалено із сервера та кешу", isError = false)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorLogs = errorLogSink.readAll(),
                            snackbarEvent = UiMessageEvent(
                                message = err.message ?: "Не вдалося видалити прогноз",
                                isError = true
                            )
                        )
                    }
                }
        }
    }

    fun generateForecastsForSelectedStation(days: Int = 3) {
        val stationId = _uiState.value.selectedStationId ?: _uiState.value.stations.firstOrNull()?.id ?: 1
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true, retryBanner = null) }
            repository.generateForecasts(stationId, days)
                .onSuccess { generated ->
                    fetchForecastsInternal(
                        stationId = _uiState.value.selectedStationId,
                        forceRefresh = true,
                        silentSyncSnackbar = true
                    )
                    emitMessage("✓ Згенеровано ${generated.size} нових прогнозів", isError = false)
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorLogs = errorLogSink.readAll(),
                            snackbarEvent = UiMessageEvent(
                                message = err.message ?: "Помилка генерації прогнозів",
                                isError = true
                            )
                        )
                    }
                }
        }
    }

    /**
     * Демонстрація запиту на сьогодні із підтримкою автоматичного retry (ПЗ №12).
     * При [flaky] = true перші 2 запити повертають HTTP 503, а 3-я спроба успішно відновлюється.
     */
    fun fetchTodayForecast(flaky: Boolean) {
        viewModelScope.launch(coroutineExceptionHandler) {
            if (flaky) mockBackend.resetFlakyCounter()
            _uiState.update { it.copy(isLoading = true, retryBanner = null) }

            val result = repository.getTodayForecastWithRetry(flaky = flaky) { attempt, max, reason ->
                _uiState.update {
                    it.copy(retryBanner = "Тимчасовий збій ($attempt/$max): $reason — повтор через 1 с...")
                }
            }

            result.onSuccess { dto ->
                _uiState.update {
                    it.copy(
                        todayForecast = dto,
                        isLoading = false,
                        retryBanner = null,
                        errorLogs = errorLogSink.readAll(),
                        snackbarEvent = UiMessageEvent(
                            message = if (flaky) {
                                "✓ Запит відновлено після автоматичного retry!"
                            } else {
                                "✓ Оперативний прогноз на ${dto.date} отримано"
                            },
                            isError = false
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        retryBanner = null,
                        errorLogs = errorLogSink.readAll(),
                        snackbarEvent = UiMessageEvent(
                            message = err.message ?: "Помилка отримання прогнозу",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    /**
     * Симулює конкретний код HTTP-помилки (`400`, `404`, `500`) для демонстрації
     * централізованого обробника [ua.kpi.windpower.data.network.NetworkErrorHandler] та журналу.
     */
    fun simulateHttpError(code: Int) {
        viewModelScope.launch(coroutineExceptionHandler) {
            _uiState.update { it.copy(isLoading = true, retryBanner = null) }
            val result = repository.simulateHttpError(code) { attempt, max, reason ->
                _uiState.update {
                    it.copy(retryBanner = "Повторна спроба $attempt/$max після $reason...")
                }
            }
            result.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        retryBanner = null,
                        errorLogs = errorLogSink.readAll(),
                        snackbarEvent = UiMessageEvent(
                            message = err.message ?: "Помилка HTTP $code",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    fun toggleSimulatedOffline(offline: Boolean) {
        networkMonitor.simulatedOffline = offline
        _uiState.update {
            it.copy(
                isOfflineSimulated = offline,
                snackbarEvent = UiMessageEvent(
                    message = if (offline) "Активовано автономний режим роботи з кешем"
                    else "Мережевий канал відновлено",
                    isError = offline
                )
            )
        }
    }

    fun toggleRealServerMode(useRealServer: Boolean) {
        mockBackend.enabled = !useRealServer
        _uiState.update {
            it.copy(
                useRealServer = useRealServer,
                snackbarEvent = UiMessageEvent(
                    message = if (useRealServer) "Режим зовнішнього сервера: http://10.0.2.2:5156/"
                    else "Режим локального шлюзу SCADA",
                    isError = false
                )
            )
        }
    }

    fun clearErrorLogs() {
        errorLogSink.clear()
        _uiState.update {
            it.copy(
                errorLogs = emptyList(),
                snackbarEvent = UiMessageEvent("Журнал error_log.txt очищено", isError = false)
            )
        }
    }

    fun refreshErrorLogs() {
        _uiState.update { it.copy(errorLogs = errorLogSink.readAll()) }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarEvent = null) }
    }

    private fun emitMessage(text: String, isError: Boolean) {
        _uiState.update { it.copy(snackbarEvent = UiMessageEvent(message = text, isError = isError)) }
    }

    private fun formatNow(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
