package ua.kpi.windpower.data.geo

import ua.kpi.windpower.data.WindParkModel
import ua.kpi.windpower.data.db.WindStationSeedCatalog
import ua.kpi.windpower.data.db.entities.WindStation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Класифікація вітрових енергетичних об'єктів за типом розміщення (ПЗ №14–16).
 */
enum class WindPlantType(val title: String) {
    ONSHORE("Наземна ВЕС"),
    COASTAL("Прибережна ВЕС"),
    HYBRID("Гібрид ВЕС+АКБ")
}

/**
 * Поточний диспетчерський статус роботи вітроелектростанції.
 */
enum class WindPlantStatus(val title: String) {
    ACTIVE("Активна"),
    PEAK("Пікове навантаження"),
    MAINTENANCE("Обслуговування")
}

/**
 * Критерії сортування динамічного списку станцій ВЕС у `LazyColumn` (ПЗ №15).
 */
enum class StationSortOption(val title: String) {
    NAME_ASC("Назва (А→Я)"),
    TYPE("Тип ВЕС"),
    POWER_DESC("Потужність ↓"),
    POWER_ASC("Потужність ↑"),
    WIND_DESC("Вітер ↓"),
    DATE_DESC("Оновлення ↓"),
    DISTANCE_ASC("Ближчі (GPS)")
}

/**
 * Поріг мінімальної встановленої потужності станції для багаторівневої фільтрації (ПЗ №15).
 */
enum class MinPowerFilter(val label: String, val minMw: Double) {
    ALL("Усі МВт", 0.0),
    MW_100("≥ 100 МВт", 100.0),
    MW_150("≥ 150 МВт", 150.0),
    MW_200("≥ 200 МВт", 200.0)
}

/**
 * Географічні координати у системі WGS-84.
 */
data class GeoPointCoord(
    val latitude: Double,
    val longitude: Double
)

/**
 * Модель енергетичного об'єкта вітрової електростанції з геопросторовими
 * й телеметричними атрибутами для відображення у `LazyColumn` та на карті (ПЗ №14–16).
 */
data class WindGeoStation(
    val id: Int,
    val name: String,
    val region: String,
    val type: WindPlantType,
    val status: WindPlantStatus,
    val capacityMw: Double,
    val currentPowerMw: Double,
    val windSpeedMps: Double,
    val turbinesCount: Int,
    val latitude: Double,
    val longitude: Double,
    val lastUpdated: String,
    val description: String
) {
    /** Коефіцієнт використання встановленої потужності у відсотках. */
    val loadFactorPercent: Double
        get() = if (capacityMw <= 0.0) 0.0 else ((currentPowerMw / capacityMw) * 1000.0).roundToInt() / 10.0
}

/**
 * Утиліта геопросторових обчислень, багаторівневої фільтрації та сортування станцій.
 */
object GeoDistanceCalculator {

    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Обчислює ортодромну відстань між двома точками на сфері Землі (км)
     * за формулою Гаверсінуса.
     */
    fun distanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return ((EARTH_RADIUS_KM * c) * 10.0).roundToInt() / 10.0
    }

    /**
     * Виконує багаторівневу фільтрацію (за пошуковим запитом, типом ВЕС, статусом роботи
     * та мінімальною потужністю) і сортування колекції станцій.
     */
    fun filterAndSort(
        stations: List<WindGeoStation>,
        searchQuery: String,
        typeFilter: WindPlantType?,
        statusFilter: WindPlantStatus?,
        minPowerFilter: MinPowerFilter,
        sortOption: StationSortOption,
        userLocation: GeoPointCoord?
    ): List<WindGeoStation> {
        val normalizedQuery = searchQuery.trim()
        val filtered = stations.filter { station ->
            val matchesQuery = normalizedQuery.isEmpty() ||
                station.name.contains(normalizedQuery, ignoreCase = true) ||
                station.region.contains(normalizedQuery, ignoreCase = true) ||
                station.description.contains(normalizedQuery, ignoreCase = true)

            val matchesType = typeFilter == null || station.type == typeFilter
            val matchesStatus = statusFilter == null || station.status == statusFilter
            val matchesPower = station.capacityMw >= minPowerFilter.minMw

            matchesQuery && matchesType && matchesStatus && matchesPower
        }

        return when (sortOption) {
            StationSortOption.NAME_ASC -> filtered.sortedBy { it.name }
            StationSortOption.TYPE -> filtered.sortedWith(compareBy({ it.type.title }, { it.name }))
            StationSortOption.POWER_DESC -> filtered.sortedByDescending { it.capacityMw }
            StationSortOption.POWER_ASC -> filtered.sortedBy { it.capacityMw }
            StationSortOption.WIND_DESC -> filtered.sortedByDescending { it.windSpeedMps }
            StationSortOption.DATE_DESC -> filtered.sortedByDescending { it.lastUpdated }
            StationSortOption.DISTANCE_ASC -> if (userLocation == null) {
                filtered.sortedBy { it.id }
            } else {
                filtered.sortedBy { st ->
                    distanceKm(userLocation.latitude, userLocation.longitude, st.latitude, st.longitude)
                }
            }
        }
    }
}

/**
 * Адаптер довідника та перетворення записів Room [WindStation] у геопросторову модель [WindGeoStation].
 */
object WindGeoCatalog {

    val initialStations: List<WindGeoStation> = WindStationSeedCatalog.entries.map { spec ->
        fromRoomStation(
            entity = spec.station,
            initialPowerMw = spec.initialPowersMw.firstOrNull(),
            initialWindMps = spec.initialSpeedsMps.firstOrNull()
        )
    }

    /**
     * Перетворює сутність Room [WindStation] у геопросторову модель карти та списку.
     */
    fun fromRoomStation(
        entity: WindStation,
        initialPowerMw: Double? = null,
        initialWindMps: Double? = null,
        timestampLabel: String = "2026-11-23 22:15"
    ): WindGeoStation {
        val plantType = when {
            entity.plantType.contains("Прибереж", ignoreCase = true) -> WindPlantType.COASTAL
            entity.plantType.contains("Гібрид", ignoreCase = true) -> WindPlantType.HYBRID
            else -> WindPlantType.ONSHORE
        }
        val status = when {
            entity.status.contains("Піков", ignoreCase = true) -> WindPlantStatus.PEAK
            entity.status.contains("Обслугов", ignoreCase = true) -> WindPlantStatus.MAINTENANCE
            else -> WindPlantStatus.ACTIVE
        }
        val defaultWind = initialWindMps ?: when (status) {
            WindPlantStatus.PEAK -> 13.6
            WindPlantStatus.MAINTENANCE -> 6.5
            WindPlantStatus.ACTIVE -> 10.4
        }
        val defaultPower = initialPowerMw ?: when (status) {
            WindPlantStatus.PEAK -> ((entity.installedCapacityMw * 0.94) * 10.0).roundToInt() / 10.0
            WindPlantStatus.MAINTENANCE -> ((entity.installedCapacityMw * 0.18) * 10.0).roundToInt() / 10.0
            WindPlantStatus.ACTIVE -> ((entity.installedCapacityMw * 0.78) * 10.0).roundToInt() / 10.0
        }
        return WindGeoStation(
            id = entity.id,
            name = entity.name,
            region = entity.location,
            type = plantType,
            status = status,
            capacityMw = entity.installedCapacityMw,
            currentPowerMw = defaultPower,
            windSpeedMps = defaultWind,
            turbinesCount = entity.turbinesCount,
            latitude = entity.latitude,
            longitude = entity.longitude,
            lastUpdated = timestampLabel,
            description = entity.description.ifBlank {
                "${entity.location} • ${entity.turbinesCount} вітроустановок, номінал ${entity.installedCapacityMw} МВт."
            }
        )
    }

    /**
     * Оновлює поточні вітрові показники станцій на базі фізичної кривої генерації парку.
     */
    fun refreshStationTelemetry(
        station: WindGeoStation,
        deltaWindMps: Double,
        timestampLabel: String
    ): WindGeoStation {
        if (station.status == WindPlantStatus.MAINTENANCE) {
            val safeWind = (station.windSpeedMps + deltaWindMps * 0.4).coerceIn(2.0, 12.0)
            return station.copy(
                windSpeedMps = ((safeWind * 10.0).roundToInt()) / 10.0,
                currentPowerMw = ((station.capacityMw * 0.15) * 10.0).roundToInt() / 10.0,
                lastUpdated = timestampLabel
            )
        }
        val nextWind = (station.windSpeedMps + deltaWindMps).coerceIn(1.5, 26.5)
        val roundedWind = ((nextWind * 10.0).roundToInt()) / 10.0
        val samplePark = WindParkModel.resolveTelemetry(roundedWind)
        val parkRatio = (samplePark.powerMw / 16.0).coerceIn(0.0, 1.0)
        val scaledPower = ((station.capacityMw * parkRatio) * 10.0).roundToInt() / 10.0
        val nextStatus = when {
            roundedWind in 12.0..25.0 && parkRatio >= 0.9 -> WindPlantStatus.PEAK
            else -> WindPlantStatus.ACTIVE
        }
        return station.copy(
            windSpeedMps = roundedWind,
            currentPowerMw = scaledPower,
            status = nextStatus,
            lastUpdated = timestampLabel
        )
    }
}
