package ua.kpi.windpower.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ua.kpi.windpower.data.db.entities.WindForecastCacheEntity

/**
 * DAO локального кешу прогнозів вітрогенерації у Room Database (ПЗ №9).
 */
@Dao
interface WindForecastCacheDao {

    @Query("SELECT * FROM wind_forecasts_cache ORDER BY date ASC, id ASC")
    suspend fun getAll(): List<WindForecastCacheEntity>

    @Query("SELECT * FROM wind_forecasts_cache WHERE stationId = :stationId ORDER BY date ASC, id ASC")
    suspend fun getByStation(stationId: Int): List<WindForecastCacheEntity>

    @Query("SELECT * FROM wind_forecasts_cache WHERE id = :id LIMIT 1")
    suspend fun findById(id: Int): WindForecastCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<WindForecastCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WindForecastCacheEntity)

    @Query("DELETE FROM wind_forecasts_cache WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM wind_forecasts_cache WHERE stationId = :stationId")
    suspend fun deleteByStation(stationId: Int)

    @Query("DELETE FROM wind_forecasts_cache")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(items: List<WindForecastCacheEntity>) {
        clearAll()
        if (items.isNotEmpty()) insertAll(items)
    }

    @Transaction
    suspend fun replaceForStation(stationId: Int, items: List<WindForecastCacheEntity>) {
        deleteByStation(stationId)
        if (items.isNotEmpty()) insertAll(items)
    }
}
