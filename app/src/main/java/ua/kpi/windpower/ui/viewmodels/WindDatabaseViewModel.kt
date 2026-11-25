package ua.kpi.windpower.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.kpi.windpower.data.db.WindStationSeedCatalog
import ua.kpi.windpower.data.db.entities.WindPowerRecord
import ua.kpi.windpower.data.db.entities.WindSpeedRecord
import ua.kpi.windpower.data.db.entities.WindStation
import ua.kpi.windpower.data.db.entities.WindVoltageRecord
import ua.kpi.windpower.di.AppContainer

/**
 * Результат операції запису/оновлення у Room БД.
 */
sealed interface AddStationResult {
    data object Success : AddStationResult
    data class Error(val message: String) : AddStationResult
}

/**
 * Агрегований зріз телеметрії обраної станції (реактивно оновлюється з Room).
 */
data class StationAnalytics(
    val powers: List<WindPowerRecord> = emptyList(),
    val speeds: List<WindSpeedRecord> = emptyList(),
    val voltages: List<WindVoltageRecord> = emptyList(),
    val averagePower: Double? = null,
    val averageSpeed: Double? = null
)

/**
 * ViewModel локального реляційного сховища Room ([WindStation]).
 *
 * Забезпечує повний CRUD станцій ВЕС, дозапис телеметричних замірів,
 * реактивні вибірки SQL-агрегатів (AVG) та відновлення каталогу за замовчуванням.
 */
class WindDatabaseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppContainer.get(application).windStationRepository

    /** Реактивний список станцій: миттєво синхронізований з таблицею wind_stations. */
    val stations: StateFlow<List<WindStation>> = repository.observeAllStations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedStationId = MutableStateFlow<Int?>(null)
    val selectedStationId: StateFlow<Int?> = _selectedStationId.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /** Реактивний зріз аналітики для обраної станції (історії + SQL-агрегати AVG). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val analytics: StateFlow<StationAnalytics> = _selectedStationId
        .flatMapLatest { stationId ->
            if (stationId == null) {
                flowOf(StationAnalytics())
            } else {
                combine(
                    repository.observePowersForStation(stationId),
                    repository.observeSpeedsForStation(stationId),
                    repository.observeVoltagesForStation(stationId),
                    repository.observeAveragePower(stationId),
                    repository.observeAverageSpeed(stationId)
                ) { powers, speeds, voltages, avgPower, avgSpeed ->
                    StationAnalytics(
                        powers = powers,
                        speeds = speeds,
                        voltages = voltages,
                        averagePower = avgPower,
                        averageSpeed = avgSpeed
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StationAnalytics())

    init {
        viewModelScope.launch {
            stations.collect { list ->
                val current = _selectedStationId.value
                if (list.isEmpty()) {
                    _selectedStationId.value = null
                } else if (current == null || list.none { it.id == current }) {
                    _selectedStationId.value = list.first().id
                }
            }
        }
    }

    fun selectStation(stationId: Int) {
        _selectedStationId.value = stationId
    }

    /**
     * Транзакційно зберігає нову станцію разом із масивами телеметрії.
     */
    fun addStationWithTelemetry(
        name: String,
        location: String,
        capacity: Double,
        turbinesCount: Int = 10,
        plantType: String = "Наземна ВЕС",
        status: String = "Активна",
        latitude: Double? = null,
        longitude: Double? = null,
        description: String = "",
        powers: List<Double>,
        speeds: List<Double>,
        voltages: List<Double>,
        onResult: (AddStationResult) -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val trimmedName = name.trim()
                if (repository.isStationNameTaken(trimmedName)) {
                    onResult(AddStationResult.Error("ВЕС із назвою «$trimmedName» вже зареєстрована у базі"))
                    return@launch
                }
                val cleanLocation = location.trim().ifBlank { "Україна" }
                val (defaultLat, defaultLon) = WindStationSeedCatalog.approximateRegionCoordinates(cleanLocation)
                val newId = repository.insertStationWithTelemetry(
                    station = WindStation(
                        name = trimmedName,
                        location = cleanLocation,
                        installedCapacityMw = capacity,
                        turbinesCount = turbinesCount.coerceAtLeast(1),
                        plantType = plantType.ifBlank { "Наземна ВЕС" },
                        status = status.ifBlank { "Активна" },
                        latitude = latitude ?: defaultLat,
                        longitude = longitude ?: defaultLon,
                        description = description.trim()
                    ),
                    powers = powers,
                    speeds = speeds,
                    voltages = voltages
                )
                _selectedStationId.value = newId.toInt()
                onResult(AddStationResult.Success)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Оновлює паспортні й геопросторові параметри існуючої ВЕС у Room БД.
     */
    fun updateStation(
        station: WindStation,
        onResult: (AddStationResult) -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val trimmedName = station.name.trim()
                if (repository.isStationNameTakenByAnother(trimmedName, station.id)) {
                    onResult(AddStationResult.Error("Інша станція вже має назву «$trimmedName»"))
                    return@launch
                }
                repository.updateStation(
                    station.copy(
                        name = trimmedName,
                        location = station.location.trim().ifBlank { "Україна" },
                        turbinesCount = station.turbinesCount.coerceAtLeast(1),
                        description = station.description.trim()
                    )
                )
                onResult(AddStationResult.Success)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Додає нові заміри телеметрії (потужність, швидкість вітру, напруга) до існуючої ВЕС.
     */
    fun addTelemetryMeasurements(
        stationId: Int,
        powers: List<Double>,
        speeds: List<Double>,
        voltages: List<Double>,
        onResult: (AddStationResult) -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (powers.isEmpty() && speeds.isEmpty() && voltages.isEmpty()) {
                    onResult(AddStationResult.Error("Вкажіть хоча б один коректний вимір"))
                    return@launch
                }
                repository.addTelemetryMeasurements(stationId, powers, speeds, voltages)
                onResult(AddStationResult.Success)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Видаляє станцію каскадно разом з усіма записами телеметрії.
     */
    fun deleteStation(station: WindStation, onDeleted: (WindStation) -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteStation(station)
            if (_selectedStationId.value == station.id) {
                _selectedStationId.value = null
            }
            onDeleted(station)
        }
    }

    /**
     * Відновлює довідник реальних ВЕС України з початковою телеметрією.
     */
    fun restoreDefaultStations(onCompleted: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val restored = repository.restoreDefaultStationsIfMissing()
                onCompleted(restored)
            } finally {
                _isSaving.value = false
            }
        }
    }
}
