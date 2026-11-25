package ua.kpi.windpower.data.datasource

import ua.kpi.windpower.data.db.dao.WindForecastCacheDao
import ua.kpi.windpower.data.db.entities.WindForecastCacheEntity
import ua.kpi.windpower.data.remote.dto.WindForecastDto

/**
 * Режими сортування прогнозів вітрогенерації у UI.
 */
enum class ForecastSortMode(val label: String) {
    DATE_ASC("Дата ↑"),
    POWER_DESC("Потужність ↓"),
    POWER_ASC("Потужність ↑"),
    WIND_DESC("Вітер ↓")
}

/**
 * Абстракція локального джерела даних (Local Data Source — ПЗ №9).
 *
 * Інкапсулює доступ до локального сховища (Room / кеш у пам'яті),
 * дозволяючи [ua.kpi.windpower.data.repository.WindForecastRepository]
 * працювати незалежно від конкретної реалізації бази даних.
 */
interface LocalWindDataSource {
    suspend fun getAll(): List<WindForecastDto>
    suspend fun getByStation(stationId: Int): List<WindForecastDto>
    suspend fun findById(id: Int): WindForecastDto?
    suspend fun replaceAll(items: List<WindForecastDto>)
    suspend fun replaceForStation(stationId: Int, items: List<WindForecastDto>)
    suspend fun insert(item: WindForecastDto)
    suspend fun update(item: WindForecastDto)
    suspend fun delete(id: Int)
    suspend fun clear()
}

/**
 * Спільна функція фільтрації та сортування прогнозів без дублювання логіки між DataSource.
 */
fun List<WindForecastDto>.filterAndSortForecasts(
    query: String,
    sortMode: ForecastSortMode
): List<WindForecastDto> {
    val trimmed = query.trim()
    val filtered = if (trimmed.isEmpty()) {
        this
    } else {
        filter {
            it.date.contains(trimmed, ignoreCase = true) ||
                (it.notes?.contains(trimmed, ignoreCase = true) == true)
        }
    }
    return when (sortMode) {
        ForecastSortMode.DATE_ASC -> filtered.sortedWith(compareBy({ it.date }, { it.id }))
        ForecastSortMode.POWER_DESC -> filtered.sortedByDescending { it.predictedPowerMw }
        ForecastSortMode.POWER_ASC -> filtered.sortedBy { it.predictedPowerMw }
        ForecastSortMode.WIND_DESC -> filtered.sortedByDescending { it.windSpeedMps }
    }
}

/**
 * Локальне джерело даних на базі Room Database (таблиця `wind_forecasts_cache`).
 * Забезпечує збереження кешу прогнозів на диск для повноцінного офлайн-режиму.
 */
class RoomLocalWindDataSource(
    private val dao: WindForecastCacheDao
) : LocalWindDataSource {

    override suspend fun getAll(): List<WindForecastDto> =
        dao.getAll().map { it.toDto() }

    override suspend fun getByStation(stationId: Int): List<WindForecastDto> =
        dao.getByStation(stationId).map { it.toDto() }

    override suspend fun findById(id: Int): WindForecastDto? =
        dao.findById(id)?.toDto()

    override suspend fun replaceAll(items: List<WindForecastDto>) {
        dao.replaceAll(items.map { WindForecastCacheEntity.fromDto(it) })
    }

    override suspend fun replaceForStation(stationId: Int, items: List<WindForecastDto>) {
        dao.replaceForStation(stationId, items.map { WindForecastCacheEntity.fromDto(it) })
    }

    override suspend fun insert(item: WindForecastDto) {
        dao.upsert(WindForecastCacheEntity.fromDto(item))
    }

    override suspend fun update(item: WindForecastDto) {
        dao.upsert(WindForecastCacheEntity.fromDto(item))
    }

    override suspend fun delete(id: Int) {
        dao.deleteById(id)
    }

    override suspend fun clear() {
        dao.clearAll()
    }
}

/**
 * Локальне джерело даних у пам'яті для швидких модульних JVM-тестів без SQLite/Android.
 */
class InMemoryLocalWindDataSource : LocalWindDataSource {

    private val data = mutableListOf<WindForecastDto>()

    override suspend fun getAll(): List<WindForecastDto> = data.toList()

    override suspend fun getByStation(stationId: Int): List<WindForecastDto> =
        data.filter { it.stationId == stationId }

    override suspend fun findById(id: Int): WindForecastDto? =
        data.firstOrNull { it.id == id }

    override suspend fun replaceAll(items: List<WindForecastDto>) {
        data.clear()
        data.addAll(items)
    }

    override suspend fun replaceForStation(stationId: Int, items: List<WindForecastDto>) {
        data.removeAll { it.stationId == stationId }
        data.addAll(items)
    }

    override suspend fun insert(item: WindForecastDto) {
        data.removeAll { it.id == item.id }
        data.add(item)
    }

    override suspend fun update(item: WindForecastDto) {
        val index = data.indexOfFirst { it.id == item.id }
        if (index >= 0) data[index] = item else data.add(item)
    }

    override suspend fun delete(id: Int) {
        data.removeAll { it.id == id }
    }

    override suspend fun clear() {
        data.clear()
    }
}
