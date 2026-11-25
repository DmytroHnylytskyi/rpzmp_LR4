package ua.kpi.windpower.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.kpi.windpower.data.geo.AndroidLocationTracker
import ua.kpi.windpower.data.geo.GeoDistanceCalculator
import ua.kpi.windpower.data.geo.GeoLocationSource
import ua.kpi.windpower.data.geo.MinPowerFilter
import ua.kpi.windpower.data.geo.StationSortOption
import ua.kpi.windpower.data.geo.UserGeoPosition
import ua.kpi.windpower.data.geo.WindGeoCatalog
import ua.kpi.windpower.data.geo.WindGeoStation
import ua.kpi.windpower.data.geo.WindPlantStatus
import ua.kpi.windpower.data.geo.WindPlantType
import ua.kpi.windpower.di.AppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Стан активних критеріїв багаторівневої фільтрації й сортування (ПЗ №15).
 */
data class StationFilterCriteria(
    val type: WindPlantType? = null,
    val status: WindPlantStatus? = null,
    val minPower: MinPowerFilter = MinPowerFilter.ALL,
    val sortOption: StationSortOption = StationSortOption.POWER_DESC
) {
    val hasActiveFilters: Boolean
        get() = type != null || status != null || minPower != MinPowerFilter.ALL
}

/**
 * Режим візуалізації картографічної підкладки (OpenStreetMap або автономний SCADA-радар).
 */
enum class MapRenderMode(val label: String) {
    OSM("🗺 OpenStreetMap"),
    VECTOR_RADAR("📡 SCADA-радар")
}

private data class LiveStationOverride(
    val currentPowerMw: Double,
    val windSpeedMps: Double,
    val status: WindPlantStatus,
    val lastUpdated: String
)

/**
 * ViewModel геопросторового реєстру ВЕС (ПЗ №13, №14, №15, №16).
 *
 * Синхронізується безпосередньо з таблицею `wind_stations` бази даних Room,
 * об'єднує потоки фільтрації (`combine` + `debounce`), підписується на GPS
 * через [AndroidLocationTracker] та керує двостороннім вибором на карті й у `LazyColumn`.
 */
@OptIn(FlowPreview::class)
class WindStationsMapViewModel(application: Application) : AndroidViewModel(application) {

    private val stationRepository = AppContainer.get(application).windStationRepository
    private val locationTracker = AndroidLocationTracker(application)

    private val _liveOverrides = MutableStateFlow<Map<Int, LiveStationOverride>>(emptyMap())

    val allStations: StateFlow<List<WindGeoStation>> = combine(
        stationRepository.observeAllStations(),
        _liveOverrides
    ) { entities, overrides ->
        entities.map { entity ->
            val base = WindGeoCatalog.fromRoomStation(entity)
            overrides[entity.id]?.let { ov ->
                base.copy(
                    currentPowerMw = ov.currentPowerMw,
                    windSpeedMps = ov.windSpeedMps,
                    status = ov.status,
                    lastUpdated = ov.lastUpdated
                )
            } ?: base
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterCriteria = MutableStateFlow(StationFilterCriteria())
    val filterCriteria: StateFlow<StationFilterCriteria> = _filterCriteria.asStateFlow()

    private val _hasLocationPermission = MutableStateFlow(locationTracker.hasLocationPermission())
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    private val _userPosition = MutableStateFlow(
        locationTracker.getLastKnownPosition() ?: AndroidLocationTracker.DEFAULT_DISPATCHER_POSITION
    )
    val userPosition: StateFlow<UserGeoPosition> = _userPosition.asStateFlow()

    private val _selectedStationId = MutableStateFlow<Int?>(null)
    val selectedStationId: StateFlow<Int?> = _selectedStationId.asStateFlow()

    private val _mapRenderMode = MutableStateFlow(MapRenderMode.OSM)
    val mapRenderMode: StateFlow<MapRenderMode> = _mapRenderMode.asStateFlow()

    /** Лічильник подій фокусування карти на вибраній станції або позиції користувача. */
    private val _cameraFocusVersion = MutableStateFlow(0)
    val cameraFocusVersion: StateFlow<Int> = _cameraFocusVersion.asStateFlow()

    private val _cameraFocusTarget = MutableStateFlow<Pair<Double, Double>?>(null)
    val cameraFocusTarget: StateFlow<Pair<Double, Double>?> = _cameraFocusTarget.asStateFlow()

    /**
     * Реактивний відфільтрований і відсортований список станцій на базі Flow `combine` + `debounce`.
     */
    val filteredStations: StateFlow<List<WindGeoStation>> = combine(
        allStations,
        _searchQuery.debounce(150L).distinctUntilChanged(),
        _filterCriteria,
        _userPosition
    ) { stations, query, criteria, userPos ->
        GeoDistanceCalculator.filterAndSort(
            stations = stations,
            searchQuery = query,
            typeFilter = criteria.type,
            statusFilter = criteria.status,
            minPowerFilter = criteria.minPower,
            sortOption = criteria.sortOption,
            userLocation = userPos.toCoord()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    private var locationJob: Job? = null

    init {
        if (_hasLocationPermission.value) {
            startLocationCollection()
        }
        viewModelScope.launch {
            allStations.collect { list ->
                val current = _selectedStationId.value
                if (list.isEmpty()) {
                    _selectedStationId.value = null
                } else if (current == null || list.none { it.id == current }) {
                    _selectedStationId.value = list.first().id
                }
            }
        }
    }

    /**
     * Обробляє результат запиту runtime-дозволів на геолокацію (`ACCESS_FINE_LOCATION` / `COARSE`).
     */
    fun onLocationPermissionResult(granted: Boolean) {
        val actuallyGranted = granted || locationTracker.hasLocationPermission()
        _hasLocationPermission.value = actuallyGranted
        if (actuallyGranted) {
            startLocationCollection()
        } else {
            locationJob?.cancel()
            _userPosition.value = _userPosition.value.copy(
                source = GeoLocationSource.PermissionDeniedFallback,
                updatedLabel = AndroidLocationTracker.formatNow()
            )
        }
    }

    /**
     * Запускає потокове прослуховування змін GPS-координат пристрою.
     */
    fun startLocationCollection() {
        if (!locationTracker.hasLocationPermission()) return
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationTracker.observeLocationUpdates().collect { position ->
                _userPosition.value = position
            }
        }
    }

    /**
     * Встановлює координати пункту базування диспетчера.
     */
    fun selectSimulatedDispatcherCity(cityName: String, lat: Double, lon: Double) {
        locationJob?.cancel()
        _userPosition.value = UserGeoPosition(
            latitude = lat,
            longitude = lon,
            accuracyMeters = null,
            source = GeoLocationSource.SimulatedPreset(cityName),
            updatedLabel = AndroidLocationTracker.formatNow()
        )
        focusCameraOn(lat, lon)
    }

    /** Центрує карту на поточній позиції користувача. */
    fun focusOnUserLocation() {
        val pos = _userPosition.value
        focusCameraOn(pos.latitude, pos.longitude)
    }

    /** Обирає станцію зі списку або мапи (двостороння синхронізація ПЗ №14 і ПЗ №16). */
    fun selectStation(stationId: Int?, focusMap: Boolean = true) {
        _selectedStationId.value = stationId
        if (focusMap && stationId != null) {
            val station = allStations.value.firstOrNull { it.id == stationId }
            if (station != null) {
                focusCameraOn(station.latitude, station.longitude)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateTypeFilter(type: WindPlantType?) {
        _filterCriteria.value = _filterCriteria.value.copy(type = type)
    }

    fun updateStatusFilter(status: WindPlantStatus?) {
        _filterCriteria.value = _filterCriteria.value.copy(status = status)
    }

    fun updateMinPowerFilter(filter: MinPowerFilter) {
        _filterCriteria.value = _filterCriteria.value.copy(minPower = filter)
    }

    fun updateSortOption(option: StationSortOption) {
        _filterCriteria.value = _filterCriteria.value.copy(sortOption = option)
    }

    fun resetAllFilters() {
        _searchQuery.value = ""
        _filterCriteria.value = StationFilterCriteria()
    }

    fun setMapRenderMode(mode: MapRenderMode) {
        _mapRenderMode.value = mode
    }

    /**
     * Оновлює миттєві вітрові показники й генерацію усіх ВЕС з БД у реальному часі.
     */
    fun refreshLiveWinds() {
        val nowStamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val updatedMap = allStations.value.associate { station ->
            val delta = Random.nextDouble(-1.8, 2.0)
            val refreshed = WindGeoCatalog.refreshStationTelemetry(station, delta, nowStamp)
            station.id to LiveStationOverride(
                currentPowerMw = refreshed.currentPowerMw,
                windSpeedMps = refreshed.windSpeedMps,
                status = refreshed.status,
                lastUpdated = nowStamp
            )
        }
        _liveOverrides.value = updatedMap
    }

    private fun focusCameraOn(lat: Double, lon: Double) {
        _cameraFocusTarget.value = lat to lon
        _cameraFocusVersion.value += 1
    }
}
