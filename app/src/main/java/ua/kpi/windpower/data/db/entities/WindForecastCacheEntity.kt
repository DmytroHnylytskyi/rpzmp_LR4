package ua.kpi.windpower.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import ua.kpi.windpower.data.remote.dto.WindForecastDto

/**
 * Локально кешована сутність прогнозу вітрової генерації у базі даних Room.
 *
 * Забезпечує офлайн-доступ та миттєве читання прогнозів локальним джерелом
 * даних (`RoomLocalWindDataSource`) відповідно до архітектури Repository (ПЗ №9).
 */
@Entity(tableName = "wind_forecasts_cache")
data class WindForecastCacheEntity(
    @PrimaryKey
    val id: Int,
    val stationId: Int,
    val date: String,
    val windSpeedMps: Double,
    val predictedPowerMw: Double,
    val temperatureC: Double,
    val turbulencePercent: Int,
    val notes: String? = null
) {
    fun toDto(): WindForecastDto = WindForecastDto(
        id = id,
        stationId = stationId,
        date = date,
        windSpeedMps = windSpeedMps,
        predictedPowerMw = predictedPowerMw,
        temperatureC = temperatureC,
        turbulencePercent = turbulencePercent,
        notes = notes
    )

    companion object {
        fun fromDto(dto: WindForecastDto): WindForecastCacheEntity = WindForecastCacheEntity(
            id = dto.id,
            stationId = dto.stationId,
            date = dto.date,
            windSpeedMps = dto.windSpeedMps,
            predictedPowerMw = dto.predictedPowerMw,
            temperatureC = dto.temperatureC,
            turbulencePercent = dto.turbulencePercent,
            notes = dto.notes
        )
    }
}
