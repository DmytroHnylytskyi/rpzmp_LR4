package ua.kpi.windpower.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO-модель вітрової електростанції для REST API (серіалізація Gson).
 */
data class RemoteWindStationDto(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("location") val location: String,
    @SerializedName("capacityMw") val capacityMw: Double,
    @SerializedName("turbinesCount") val turbinesCount: Int
)

/**
 * DTO-модель метеопрогнозу генерації ВЕС для REST API (ПЗ №9 та ПЗ №10).
 *
 * Відображає структуру JSON-відповідей серверних ендпоінтів `/forecasts`
 * та `/stations/{id}/forecasts`.
 */
data class WindForecastDto(
    @SerializedName("id") val id: Int,
    @SerializedName("stationId") val stationId: Int,
    @SerializedName("date") val date: String,
    @SerializedName("windSpeedMps") val windSpeedMps: Double,
    @SerializedName("predictedPowerMw") val predictedPowerMw: Double,
    @SerializedName("temperatureC") val temperatureC: Double,
    @SerializedName("turbulencePercent") val turbulencePercent: Int,
    @SerializedName("notes") val notes: String? = null
)

/**
 * DTO-модель агрегованої статистики прогнозів для конкретної ВЕС
 * (відповідь ендпоінта `/stations/{id}/forecasts/stats`).
 */
data class WindForecastStatsDto(
    @SerializedName("stationId") val stationId: Int,
    @SerializedName("averagePowerMw") val averagePowerMw: Double,
    @SerializedName("averageWindSpeedMps") val averageWindSpeedMps: Double,
    @SerializedName("averageTemperatureC") val averageTemperatureC: Double,
    @SerializedName("averageTurbulencePercent") val averageTurbulencePercent: Double,
    @SerializedName("efficiencyPercent") val efficiencyPercent: Double
)

/**
 * Тіло HTTP-запиту на автентифікацію (`POST /login`).
 */
data class LoginRequestDto(
    @SerializedName("Username") val username: String,
    @SerializedName("Password") val password: String
)

/**
 * Тіло HTTP-запиту на реєстрацію нового диспетчера (`POST /register`).
 */
data class RegisterRequestDto(
    @SerializedName("Username") val username: String,
    @SerializedName("Password") val password: String,
    @SerializedName("FullName") val fullName: String,
    @SerializedName("Shift") val shift: String,
    @SerializedName("Role") val role: String = "USER"
)

/**
 * Відповідь сервера після успішного логіну або реєстрації (JWT-токен і профіль ролі).
 */
data class LoginResponseDto(
    @SerializedName("token") val token: String,
    @SerializedName("role") val role: String,
    @SerializedName("fullName") val fullName: String,
    @SerializedName("shift") val shift: String,
    @SerializedName("expiresAtEpochMs") val expiresAtEpochMs: Long
)

/**
 * Відповідь захищеного ресурсу `/protected` (доступний за наявності дійсного токена).
 */
data class ProtectedMessageDto(
    @SerializedName("message") val message: String,
    @SerializedName("issuedForRole") val issuedForRole: String,
    @SerializedName("serverTimestampMs") val serverTimestampMs: Long
)

/**
 * Стандартна JSON-структура повідомлення про помилку API (ПЗ №12).
 */
data class ApiErrorMessageDto(
    @SerializedName("msg") val msg: String
)
