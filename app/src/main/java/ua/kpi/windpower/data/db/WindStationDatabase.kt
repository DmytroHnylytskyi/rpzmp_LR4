package ua.kpi.windpower.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ua.kpi.windpower.data.db.dao.WindDispatcherDao
import ua.kpi.windpower.data.db.dao.WindForecastCacheDao
import ua.kpi.windpower.data.db.dao.WindStationDao
import ua.kpi.windpower.data.db.entities.DispatcherAccount
import ua.kpi.windpower.data.db.entities.WindForecastCacheEntity
import ua.kpi.windpower.data.db.entities.WindPowerRecord
import ua.kpi.windpower.data.db.entities.WindSpeedRecord
import ua.kpi.windpower.data.db.entities.WindStation
import ua.kpi.windpower.data.db.entities.WindVoltageRecord

/**
 * Головна база даних Room мобільного застосунку «AeroGrid».
 * Реляційне локальне сховище параметрів вітрових електростанцій, телеметрії та кешу.
 *
 * Версія 4 розширює таблицю `wind_stations` інженерними й геопросторовими атрибутами
 * (кількість турбін, тип розміщення, статус, координати WGS-84, опис) та наповнює
 * базу початковим довідником реальних українських ВЕС при першому розгортанні.
 */
@Database(
    entities = [
        WindStation::class,
        WindPowerRecord::class,
        WindSpeedRecord::class,
        WindVoltageRecord::class,
        DispatcherAccount::class,
        WindForecastCacheEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class WindStationDatabase : RoomDatabase() {

    abstract fun windStationDao(): WindStationDao

    abstract fun windDispatcherDao(): WindDispatcherDao

    abstract fun windForecastCacheDao(): WindForecastCacheDao

    companion object {
        private const val DATABASE_NAME = "wind_stations_database"

        @Volatile
        private var INSTANCE: WindStationDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wind_dispatchers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`login` TEXT NOT NULL, " +
                        "`fullName` TEXT NOT NULL, " +
                        "`email` TEXT NOT NULL, " +
                        "`shift` TEXT NOT NULL, " +
                        "`smsAlerts` INTEGER NOT NULL, " +
                        "`passwordHash` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_wind_dispatchers_login` " +
                        "ON `wind_dispatchers` (`login`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wind_forecasts_cache` (" +
                        "`id` INTEGER PRIMARY KEY NOT NULL, " +
                        "`stationId` INTEGER NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`windSpeedMps` REAL NOT NULL, " +
                        "`predictedPowerMw` REAL NOT NULL, " +
                        "`temperatureC` REAL NOT NULL, " +
                        "`turbulencePercent` INTEGER NOT NULL, " +
                        "`notes` TEXT)"
                )
                db.execSQL(
                    "ALTER TABLE `wind_dispatchers` ADD COLUMN `role` TEXT NOT NULL DEFAULT 'USER'"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `wind_stations` ADD COLUMN `turbinesCount` INTEGER NOT NULL DEFAULT 10"
                )
                db.execSQL(
                    "ALTER TABLE `wind_stations` ADD COLUMN `plantType` TEXT NOT NULL DEFAULT 'Наземна ВЕС'"
                )
                db.execSQL(
                    "ALTER TABLE `wind_stations` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'Активна'"
                )
                db.execSQL(
                    "ALTER TABLE `wind_stations` ADD COLUMN `latitude` REAL NOT NULL DEFAULT 48.45"
                )
                db.execSQL(
                    "ALTER TABLE `wind_stations` ADD COLUMN `longitude` REAL NOT NULL DEFAULT 31.50"
                )
                db.execSQL(
                    "ALTER TABLE `wind_stations` ADD COLUMN `description` TEXT NOT NULL DEFAULT ''"
                )
                seedIfEmpty(db)
            }
        }

        private val INITIAL_SEED_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seedIfEmpty(db)
            }
        }

        private fun seedIfEmpty(db: SupportSQLiteDatabase) {
            val count = db.query("SELECT COUNT(*) FROM `wind_stations`").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            if (count > 0) return

            WindStationSeedCatalog.entries.forEach { spec ->
                val st = spec.station
                val stationValues = ContentValues().apply {
                    put("name", st.name)
                    put("location", st.location)
                    put("installedCapacityMw", st.installedCapacityMw)
                    put("turbinesCount", st.turbinesCount)
                    put("plantType", st.plantType)
                    put("status", st.status)
                    put("latitude", st.latitude)
                    put("longitude", st.longitude)
                    put("description", st.description)
                }
                val newStationId = db.insert("wind_stations", SQLiteDatabase.CONFLICT_IGNORE, stationValues)
                if (newStationId > 0L) {
                    spec.initialPowersMw.forEach { power ->
                        val cv = ContentValues().apply {
                            put("stationId", newStationId)
                            put("value", power)
                        }
                        db.insert("wind_power_records", SQLiteDatabase.CONFLICT_IGNORE, cv)
                    }
                    spec.initialSpeedsMps.forEach { speed ->
                        val cv = ContentValues().apply {
                            put("stationId", newStationId)
                            put("value", speed)
                        }
                        db.insert("wind_speed_records", SQLiteDatabase.CONFLICT_IGNORE, cv)
                    }
                    spec.initialVoltagesV.forEach { voltage ->
                        val cv = ContentValues().apply {
                            put("stationId", newStationId)
                            put("value", voltage)
                        }
                        db.insert("wind_voltage_records", SQLiteDatabase.CONFLICT_IGNORE, cv)
                    }
                }
            }
        }

        /**
         * Повертає єдиний екземпляр бази даних у межах процесу.
         */
        fun getInstance(context: Context): WindStationDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WindStationDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .addCallback(INITIAL_SEED_CALLBACK)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
