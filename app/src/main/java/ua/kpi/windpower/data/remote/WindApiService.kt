package ua.kpi.windpower.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import ua.kpi.windpower.data.remote.dto.ApiErrorMessageDto
import ua.kpi.windpower.data.remote.dto.LoginRequestDto
import ua.kpi.windpower.data.remote.dto.LoginResponseDto
import ua.kpi.windpower.data.remote.dto.ProtectedMessageDto
import ua.kpi.windpower.data.remote.dto.RegisterRequestDto
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.remote.dto.WindForecastDto
import ua.kpi.windpower.data.remote.dto.WindForecastStatsDto

/**
 * Контракт REST API системи моніторингу та прогнозування ВЕС «AeroGrid 3.0».
 *
 * Описує всі ендпоінти для:
 * - синхронізації вітроелектростанцій і прогнозів потужності (ПЗ №9, ПЗ №10);
 * - токен-базованої автентифікації та рольового доступу RBAC (ПЗ №11);
 * - діагностики й тестування обробки HTTP-помилок та механізму retry (ПЗ №12).
 */
interface WindApiService {

    // --- Станції ВЕС (ПЗ №10) ---

    @GET("stations")
    suspend fun getStations(): List<RemoteWindStationDto>

    @GET("stations/{id}")
    suspend fun getStation(@Path("id") id: Int): RemoteWindStationDto

    @POST("stations")
    suspend fun addStation(@Body station: RemoteWindStationDto): Response<RemoteWindStationDto>

    // --- Прогнози вітру й потужності (ПЗ №9, ПЗ №10) ---

    @GET("forecasts")
    suspend fun getAllForecasts(): List<WindForecastDto>

    @POST("forecasts")
    suspend fun addForecast(@Body forecast: WindForecastDto): Response<WindForecastDto>

    @PUT("forecasts/{id}")
    suspend fun updateForecast(
        @Path("id") id: Int,
        @Body forecast: WindForecastDto
    ): Response<WindForecastDto>

    @DELETE("forecasts/{id}")
    suspend fun deleteForecast(@Path("id") id: Int): Response<Unit>

    @GET("stations/{id}/forecasts")
    suspend fun getStationForecasts(@Path("id") stationId: Int): List<WindForecastDto>

    @GET("stations/{id}/forecasts/stats")
    suspend fun getStationForecastStats(@Path("id") stationId: Int): WindForecastStatsDto

    @POST("stations/{id}/forecasts/generate/{days}")
    suspend fun generateStationForecasts(
        @Path("id") stationId: Int,
        @Path("days") days: Int
    ): List<WindForecastDto>

    // --- Автентифікація та захищені ресурси (ПЗ №11) ---

    @POST("login")
    suspend fun login(@Body request: LoginRequestDto): LoginResponseDto

    @POST("register")
    suspend fun register(@Body request: RegisterRequestDto): Response<LoginResponseDto>

    /**
     * Захищений ресурс, доступний будь-якому автентифікованому користувачу.
     * Параметр [skipAuth] використовується у діагностиці безпеки для демонстрації HTTP 401.
     */
    @GET("protected")
    suspend fun getProtectedData(
        @Header("X-Skip-Auth") skipAuth: String? = null
    ): ProtectedMessageDto

    /**
     * Адміністративний ресурс — доступний виключно з роллю `ADMIN` у JWT-токені.
     * При зверненні з роллю `USER` сервер повертає HTTP 403 Forbidden.
     */
    @GET("admin-only")
    suspend fun getAdminOnlyStations(
        @Header("X-Skip-Auth") skipAuth: String? = null
    ): List<RemoteWindStationDto>

    // --- Оперативний прогноз та симуляція мережевих помилок (ПЗ №12) ---

    @GET("forecast/today")
    suspend fun getTodayForecast(
        @Query("flaky") flaky: Boolean = false
    ): WindForecastDto

    @GET("forecast/error/{code}")
    suspend fun getError(@Path("code") code: Int): ApiErrorMessageDto
}
