package ua.kpi.windpower.data.repository

import ua.kpi.windpower.data.datasource.ForecastSortMode
import ua.kpi.windpower.data.datasource.LocalWindDataSource
import ua.kpi.windpower.data.datasource.RemoteWindDataSource
import ua.kpi.windpower.data.datasource.filterAndSortForecasts
import ua.kpi.windpower.data.network.NetworkErrorHandler
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.remote.dto.WindForecastDto
import ua.kpi.windpower.data.remote.dto.WindForecastStatsDto
import kotlin.math.roundToInt

/**
 * Походження даних, які [WindForecastRepository] повернув шару ViewModel.
 */
enum class DataOrigin(val title: String) {
    REMOTE_SYNCED("REST API (синхронізовано)"),
    CACHE_FRESH("Локальний кеш (TTL < 5 хв)"),
    OFFLINE_CACHE_FALLBACK("Офлайн-кеш (fallback)")
}

/**
 * Агрегована бізнес-аналітика прогнозів генерації вітропарку,
 * обчислена на рівні Repository (ПЗ №9).
 */
data class ForecastAnalyticsSummary(
    val count: Int = 0,
    val averageWindSpeedMps: Double = 0.0,
    val averagePowerMw: Double = 0.0,
    val peakPowerMw: Double = 0.0,
    val activeGenerationSharePercent: Double = 0.0
)

/**
 * Результат завантаження прогнозів із зазначенням джерела даних,
 * агрегованої аналітики та опційного попередження (якщо спрацював fallback на кеш).
 */
data class ForecastLoadResult(
    val items: List<WindForecastDto>,
    val origin: DataOrigin,
    val summary: ForecastAnalyticsSummary,
    val warningMessage: String? = null
)

/**
 * Репозиторій прогнозування потужності ВЕС (ПЗ №9, ПЗ №10, ПЗ №12).
 *
 * Ключові архітектурні рішення:
 * - Об'єднує [LocalWindDataSource] (кеш у Room SQLite) та [RemoteWindDataSource] (Retrofit2).
 * - Реалізує TTL-кешування (5 хвилин) для зменшення навантаження на REST API.
 * - При збої мережі або в офлайн-режимі автоматично віддає локально збережений кеш
 *   із чітким повідомленням у UI (`DataOrigin.OFFLINE_CACHE_FALLBACK`).
 * - Синхронізує CRUD-операції (`add`, `update`, `delete`) між сервером і кешем.
 * - Виконує бізнес-агрегацію метеопрогнозів у [ForecastAnalyticsSummary].
 */
class WindForecastRepository(
    private val local: LocalWindDataSource,
    private val remote: RemoteWindDataSource,
    private val errorHandler: NetworkErrorHandler,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val retryDelayMs: Long = NetworkErrorHandler.DEFAULT_RETRY_DELAY_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Час останнього успішного оновлення з сервера за ключем stationId (0 = усі станції). */
    private val lastFetchTimestamps = mutableMapOf<Int, Long>()

    /**
     * Завантажує прогнози (для конкретної ВЕС або по всьому парку), обираючи оптимальне джерело:
     * свіжий кеш або віддалений REST API із повторними спробами та fallback-механізмом.
     */
    suspend fun getForecasts(
        stationId: Int? = null,
        forceRefresh: Boolean = false,
        onRetryAttempt: (attempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> }
    ): Result<ForecastLoadResult> {
        val cacheKey = stationId ?: ALL_STATIONS_KEY
        val now = clock()
        val localData = if (stationId == null) local.getAll() else local.getByStation(stationId)
        val lastFetch = lastFetchTimestamps[cacheKey] ?: 0L
        val cacheValid = localData.isNotEmpty() && (now - lastFetch) in 0 until cacheTtlMs

        if (!forceRefresh && cacheValid) {
            return Result.success(
                ForecastLoadResult(
                    items = localData,
                    origin = DataOrigin.CACHE_FRESH,
                    summary = computeSummary(localData)
                )
            )
        }

        val remoteResult = errorHandler.executeWithRetry(
            retryDelayMs = retryDelayMs,
            onRetryAttempt = onRetryAttempt
        ) {
            if (stationId == null) remote.fetchAllForecasts()
            else remote.fetchStationForecasts(stationId)
        }

        return remoteResult.fold(
            onSuccess = { remoteItems ->
                if (stationId == null) {
                    local.replaceAll(remoteItems)
                } else {
                    local.replaceForStation(stationId, remoteItems)
                }
                lastFetchTimestamps[cacheKey] = clock()
                Result.success(
                    ForecastLoadResult(
                        items = remoteItems,
                        origin = DataOrigin.REMOTE_SYNCED,
                        summary = computeSummary(remoteItems)
                    )
                )
            },
            onFailure = { err ->
                if (localData.isNotEmpty()) {
                    Result.success(
                        ForecastLoadResult(
                            items = localData,
                            origin = DataOrigin.OFFLINE_CACHE_FALLBACK,
                            summary = computeSummary(localData),
                            warningMessage = "${err.message}. Показано локальний кеш."
                        )
                    )
                } else {
                    Result.failure(err)
                }
            }
        )
    }

    /** Додає новий прогноз на сервер і синхронно оновлює локальний кеш. */
    suspend fun addForecast(item: WindForecastDto): Result<WindForecastDto> =
        errorHandler.executeWithRetry(maxRetries = 1, retryDelayMs = 0L) {
            val created = remote.addForecast(item)
            local.insert(created)
            created
        }

    /** Оновлює існуючий прогноз на сервері та в локальному кеші. */
    suspend fun updateForecast(item: WindForecastDto): Result<WindForecastDto> =
        errorHandler.executeWithRetry(maxRetries = 1, retryDelayMs = 0L) {
            val updated = remote.updateForecast(item)
            local.update(updated)
            updated
        }

    /** Видаляє прогноз за [id] на сервері та з локального кешу. */
    suspend fun deleteForecast(id: Int): Result<Unit> =
        errorHandler.executeWithRetry(maxRetries = 1, retryDelayMs = 0L) {
            remote.deleteForecast(id)
            local.delete(id)
        }

    /** Отримує перелік віддалених ВЕС із retry при тимчасових збоях. */
    suspend fun getStations(
        onRetryAttempt: (attempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> }
    ): Result<List<RemoteWindStationDto>> =
        errorHandler.executeWithRetry(retryDelayMs = retryDelayMs, onRetryAttempt = onRetryAttempt) {
            remote.fetchStations()
        }

    /** Отримує розраховану сервером статистику прогнозів для станції [stationId]. */
    suspend fun getStationStats(stationId: Int): Result<WindForecastStatsDto> =
        errorHandler.executeWithRetry(retryDelayMs = retryDelayMs) {
            remote.fetchStationStats(stationId)
        }

    /** Генерує нові прогнози на [days] діб для станції та зберігає їх у кеш. */
    suspend fun generateForecasts(stationId: Int, days: Int = 3): Result<List<WindForecastDto>> =
        errorHandler.executeWithRetry(retryDelayMs = retryDelayMs) {
            val created = remote.generateStationForecasts(stationId, days)
            created.forEach { local.insert(it) }
            created
        }

    /** Отримує оперативний прогноз на сьогодні (із підтримкою тестування transient 503 retry). */
    suspend fun getTodayForecastWithRetry(
        flaky: Boolean,
        onRetryAttempt: (attempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> }
    ): Result<WindForecastDto> =
        errorHandler.executeWithRetry(retryDelayMs = retryDelayMs, onRetryAttempt = onRetryAttempt) {
            remote.fetchTodayForecast(flaky)
        }

    /** Симулює конкретний код HTTP-помилки через серверний діагностичний ендпоінт. */
    suspend fun simulateHttpError(
        code: Int,
        onRetryAttempt: (attempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> }
    ): Result<Unit> =
        errorHandler.executeWithRetry(retryDelayMs = retryDelayMs, onRetryAttempt = onRetryAttempt) {
            remote.triggerServerError(code)
        }

    /** Швидка локальна фільтрація та сортування без мережевих запитів. */
    suspend fun queryLocal(
        stationId: Int?,
        query: String,
        sortMode: ForecastSortMode
    ): List<WindForecastDto> {
        val base = if (stationId == null) local.getAll() else local.getByStation(stationId)
        return base.filterAndSortForecasts(query, sortMode)
    }

    /** Скидає мітки актуальності кешу (наприклад, для тестування примусового оновлення). */
    fun invalidateCache() {
        lastFetchTimestamps.clear()
    }

    /**
     * Обчислює агреговану статистику ВЕС локально з кешу
     * (використовується в офлайн-режимі або як резерв при збої `/forecasts/stats`).
     */
    suspend fun computeStationStatsFromLocal(
        stationId: Int,
        stationCapacityMw: Double
    ): WindForecastStatsDto {
        val list = local.getByStation(stationId)
        val avgPower = if (list.isEmpty()) 0.0 else list.map { it.predictedPowerMw }.average()
        val avgWind = if (list.isEmpty()) 0.0 else list.map { it.windSpeedMps }.average()
        val avgTemp = if (list.isEmpty()) 0.0 else list.map { it.temperatureC }.average()
        val avgTurb = if (list.isEmpty()) 0.0 else list.map { it.turbulencePercent }.average()
        val eff = if (stationCapacityMw <= 0.0) 0.0 else (avgPower / stationCapacityMw) * 100.0
        return WindForecastStatsDto(
            stationId = stationId,
            averagePowerMw = round2(avgPower),
            averageWindSpeedMps = round2(avgWind),
            averageTemperatureC = round2(avgTemp),
            averageTurbulencePercent = round2(avgTurb),
            efficiencyPercent = round2(eff)
        )
    }

    /**
     * Бізнес-логіка агрегування показників прогнозів вітрової електростанції.
     */
    fun computeSummary(items: List<WindForecastDto>): ForecastAnalyticsSummary {
        if (items.isEmpty()) return ForecastAnalyticsSummary()
        val avgWind = items.map { it.windSpeedMps }.average()
        val avgPower = items.map { it.predictedPowerMw }.average()
        val peakPower = items.maxOf { it.predictedPowerMw }
        val activeShare = (items.count { it.predictedPowerMw > 0.0 } * 100.0) / items.size
        return ForecastAnalyticsSummary(
            count = items.size,
            averageWindSpeedMps = round2(avgWind),
            averagePowerMw = round2(avgPower),
            peakPowerMw = round2(peakPower),
            activeGenerationSharePercent = round2(activeShare)
        )
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val ALL_STATIONS_KEY = 0
    }
}
