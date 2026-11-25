package ua.kpi.windpower.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ua.kpi.windpower.data.db.entities.WindPowerRecord
import ua.kpi.windpower.data.db.entities.WindSpeedRecord
import ua.kpi.windpower.data.db.entities.WindStation
import ua.kpi.windpower.data.db.entities.WindVoltageRecord

/**
 * Data Access Object (DAO) для керування базою даних вітропарків Room.
 * Забезпечує реляційні вибірки, фільтрацію, статистичні обчислення (AVG)
 * та атомарні транзакції вставки й оновлення.
 *
 * Читання списків та аналітики реалізовано через реактивні [Flow]: будь-яка
 * операція вставки/оновлення/видалення автоматично оновлює всі спостерігачі UI
 * у реальному часі без ручного перечитування.
 */
@Dao
interface WindStationDao {

    // --- Станції ВЕС ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStation(station: WindStation): Long

    @Update
    suspend fun updateStation(station: WindStation)

    @Delete
    suspend fun deleteStation(station: WindStation)

    /** Реактивний список усіх станцій, відсортований за назвою. */
    @Query("SELECT * FROM wind_stations ORDER BY name ASC")
    fun observeAllStations(): Flow<List<WindStation>>

    /** Разовий зріз усіх станцій у порядку ідентифікаторів (для API-синхронізації). */
    @Query("SELECT * FROM wind_stations ORDER BY id ASC")
    suspend fun getAllStationsOnce(): List<WindStation>

    @Query("SELECT * FROM wind_stations WHERE id = :stationId LIMIT 1")
    suspend fun getStationById(stationId: Int): WindStation?

    /** Кількість станцій із заданою назвою (без урахування регістру) — для перевірки унікальності. */
    @Query("SELECT COUNT(id) FROM wind_stations WHERE name = :name COLLATE NOCASE")
    suspend fun countStationsByName(name: String): Int

    /** Перевірка унікальності назви при редагуванні існуючої станції [excludeId]. */
    @Query("SELECT COUNT(id) FROM wind_stations WHERE name = :name COLLATE NOCASE AND id != :excludeId")
    suspend fun countStationsByNameExcludingId(name: String, excludeId: Int): Int

    // --- Записи потужності ---

    @Insert
    suspend fun insertPower(power: WindPowerRecord)

    @Query("SELECT * FROM wind_power_records WHERE stationId = :stationId ORDER BY value DESC")
    fun observePowersForStation(stationId: Int): Flow<List<WindPowerRecord>>

    @Query("SELECT AVG(value) FROM wind_power_records WHERE stationId = :stationId")
    fun observeAveragePower(stationId: Int): Flow<Double?>

    // --- Записи швидкості вітру ---

    @Insert
    suspend fun insertSpeed(speed: WindSpeedRecord)

    @Query("SELECT * FROM wind_speed_records WHERE stationId = :stationId ORDER BY value DESC")
    fun observeSpeedsForStation(stationId: Int): Flow<List<WindSpeedRecord>>

    @Query("SELECT AVG(value) FROM wind_speed_records WHERE stationId = :stationId")
    fun observeAverageSpeed(stationId: Int): Flow<Double?>

    // --- Записи напруги ---

    @Insert
    suspend fun insertVoltage(voltage: WindVoltageRecord)

    @Query("SELECT * FROM wind_voltage_records WHERE stationId = :stationId ORDER BY value DESC")
    fun observeVoltagesForStation(stationId: Int): Flow<List<WindVoltageRecord>>

    // --- Комплексні атомарні транзакції ---

    /**
     * Вставляє станцію та всі пов'язані масиви телеметрії атомарно.
     */
    @Transaction
    suspend fun insertStationWithTelemetry(
        station: WindStation,
        powers: List<Double>,
        speeds: List<Double>,
        voltages: List<Double>
    ): Long {
        val stationId = insertStation(station)
        addTelemetryMeasurements(stationId.toInt(), powers, speeds, voltages)
        return stationId
    }

    /**
     * Атомарно додає нові виміри телеметрії до вже існуючої станції [stationId].
     */
    @Transaction
    suspend fun addTelemetryMeasurements(
        stationId: Int,
        powers: List<Double>,
        speeds: List<Double>,
        voltages: List<Double>
    ) {
        powers.forEach { insertPower(WindPowerRecord(stationId = stationId, value = it)) }
        speeds.forEach { insertSpeed(WindSpeedRecord(stationId = stationId, value = it)) }
        voltages.forEach { insertVoltage(WindVoltageRecord(stationId = stationId, value = it)) }
    }
}
