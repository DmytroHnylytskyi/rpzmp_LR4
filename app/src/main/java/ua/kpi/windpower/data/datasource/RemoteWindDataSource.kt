package ua.kpi.windpower.data.datasource

import retrofit2.HttpException
import retrofit2.Response
import ua.kpi.windpower.data.network.NetworkStatusProvider
import ua.kpi.windpower.data.network.OfflineNetworkException
import ua.kpi.windpower.data.remote.WindApiService
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.remote.dto.WindForecastDto
import ua.kpi.windpower.data.remote.dto.WindForecastStatsDto

/**
 * Віддалене джерело даних (Remote Data Source — ПЗ №9, ПЗ №10, ПЗ №12).
 *
 * Відповідає виключно за мережеву взаємодію з REST API через Retrofit2 + Gson,
 * попередньо перевіряючи наявність інтернет-з'єднання за допомогою [NetworkStatusProvider].
 */
class RemoteWindDataSource(
    private val api: WindApiService,
    private val networkProvider: NetworkStatusProvider
) {

    private fun ensureOnline() {
        if (!networkProvider.isInternetAvailable()) {
            throw OfflineNetworkException()
        }
    }

    private fun <T> unwrapResponse(response: Response<T>): T {
        if (response.isSuccessful) {
            return response.body() ?: throw IllegalStateException("Порожня відповідь сервера")
        }
        throw HttpException(response)
    }

    suspend fun fetchStations(): List<RemoteWindStationDto> {
        ensureOnline()
        return api.getStations()
    }

    suspend fun addStation(station: RemoteWindStationDto): RemoteWindStationDto {
        ensureOnline()
        return unwrapResponse(api.addStation(station))
    }

    suspend fun fetchAllForecasts(): List<WindForecastDto> {
        ensureOnline()
        return api.getAllForecasts()
    }

    suspend fun fetchStationForecasts(stationId: Int): List<WindForecastDto> {
        ensureOnline()
        return api.getStationForecasts(stationId)
    }

    suspend fun fetchStationStats(stationId: Int): WindForecastStatsDto {
        ensureOnline()
        return api.getStationForecastStats(stationId)
    }

    suspend fun generateStationForecasts(stationId: Int, days: Int): List<WindForecastDto> {
        ensureOnline()
        return api.generateStationForecasts(stationId, days)
    }

    suspend fun addForecast(forecast: WindForecastDto): WindForecastDto {
        ensureOnline()
        return unwrapResponse(api.addForecast(forecast))
    }

    suspend fun updateForecast(forecast: WindForecastDto): WindForecastDto {
        ensureOnline()
        return unwrapResponse(api.updateForecast(forecast.id, forecast))
    }

    suspend fun deleteForecast(id: Int) {
        ensureOnline()
        val response = api.deleteForecast(id)
        if (!response.isSuccessful) {
            throw HttpException(response)
        }
    }

    suspend fun fetchTodayForecast(flaky: Boolean = false): WindForecastDto {
        ensureOnline()
        return api.getTodayForecast(flaky)
    }

    suspend fun triggerServerError(code: Int) {
        ensureOnline()
        api.getError(code)
    }
}
