package ua.kpi.windpower.di

import android.content.Context
import kotlinx.coroutines.runBlocking
import ua.kpi.windpower.data.auth.AuthDataStore
import ua.kpi.windpower.data.auth.RemoteAuthRepository
import ua.kpi.windpower.data.datasource.RemoteWindDataSource
import ua.kpi.windpower.data.datasource.RoomLocalWindDataSource
import ua.kpi.windpower.data.db.WindStationDatabase
import ua.kpi.windpower.data.db.WindStationSeedCatalog
import ua.kpi.windpower.data.db.entities.WindStation
import ua.kpi.windpower.data.db.repository.DispatcherAuthRepository
import ua.kpi.windpower.data.db.repository.WindStationRepository
import ua.kpi.windpower.data.network.AndroidNetworkMonitor
import ua.kpi.windpower.data.network.FileErrorLogSink
import ua.kpi.windpower.data.network.NetworkErrorHandler
import ua.kpi.windpower.data.remote.MockUserAccount
import ua.kpi.windpower.data.remote.MockWindBackendInterceptor
import ua.kpi.windpower.data.remote.RetrofitClient
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.repository.WindForecastRepository

/**
 * Потокобезпечний контейнер впровадження залежностей (Service Locator) для «AeroGrid 4.0».
 *
 * Гарантує, що всі ViewModel та екрани працюють із єдиними екземплярами:
 * - бази даних Room ([WindStationDatabase]) та спільним репозиторієм станцій [WindStationRepository];
 * - мережевого клієнта Retrofit2 + AuthInterceptor ([RetrofitClient]);
 * - контролера мережі ([AndroidNetworkMonitor]) і журналу помилок ([FileErrorLogSink]);
 * - репозиторіїв [WindForecastRepository] та [RemoteAuthRepository].
 */
class AppContainer private constructor(context: Context) {

    private val appContext = context.applicationContext

    val database: WindStationDatabase = WindStationDatabase.getInstance(appContext)
    val windStationRepository = WindStationRepository(database.windStationDao())
    private val networkServices = RetrofitClient.getInstance(appContext)

    val tokenStorage: AuthDataStore = networkServices.tokenStorage
    val mockBackend: MockWindBackendInterceptor = networkServices.mockBackend.apply {
        // Підключаємо локальну таблицю wind_dispatchers, щоб зареєстровані
        // інженери могли повторно авторизуватися навіть після перезапуску застосунку
        persistedUserLookup = { login ->
            runBlocking {
                database.windDispatcherDao().findByLogin(login)?.let { acc ->
                    MockUserAccount(
                        username = acc.email,
                        passwordHash = acc.passwordHash,
                        role = acc.role,
                        fullName = acc.fullName,
                        shift = acc.shift
                    )
                }
            }
        }
        // Підключаємо локальну таблицю wind_stations як єдине джерело станцій для API
        stationsProvider = {
            runBlocking {
                windStationRepository.getAllStationsOnce().map { st ->
                    RemoteWindStationDto(
                        id = st.id,
                        name = st.name,
                        location = st.location,
                        capacityMw = st.installedCapacityMw,
                        turbinesCount = st.turbinesCount
                    )
                }
            }
        }
        onStationCreatedFromApi = { dto ->
            runBlocking {
                val trimmed = dto.name.trim()
                if (windStationRepository.isStationNameTaken(trimmed)) {
                    throw IllegalArgumentException("ВЕС із назвою «$trimmed» вже зареєстрована у базі")
                }
                val loc = dto.location.trim().ifBlank { "Україна" }
                val (lat, lon) = WindStationSeedCatalog.approximateRegionCoordinates(loc)
                val (powers, speeds, voltages) = WindStationSeedCatalog.defaultTelemetryFor(dto.capacityMw)
                val newId = windStationRepository.insertStationWithTelemetry(
                    station = WindStation(
                        name = trimmed,
                        location = loc,
                        installedCapacityMw = dto.capacityMw,
                        turbinesCount = dto.turbinesCount.coerceAtLeast(1),
                        plantType = "Наземна ВЕС",
                        status = "Активна",
                        latitude = lat,
                        longitude = lon,
                        description = "Об'єкт додано через адміністративну панель SCADA."
                    ),
                    powers = powers,
                    speeds = speeds,
                    voltages = voltages
                )
                dto.copy(id = newId.toInt(), name = trimmed, location = loc)
            }
        }
    }
    val networkMonitor = AndroidNetworkMonitor(appContext)
    val errorLogSink = FileErrorLogSink(appContext)
    val errorHandler = NetworkErrorHandler(errorLogSink)

    private val localWindDataSource = RoomLocalWindDataSource(database.windForecastCacheDao())
    private val remoteWindDataSource = RemoteWindDataSource(
        api = networkServices.api,
        networkProvider = networkMonitor
    )

    val forecastRepository = WindForecastRepository(
        local = localWindDataSource,
        remote = remoteWindDataSource,
        errorHandler = errorHandler
    )

    val remoteAuthRepository = RemoteAuthRepository(
        api = networkServices.api,
        tokenStorage = tokenStorage,
        networkProvider = networkMonitor,
        errorHandler = errorHandler
    )

    val localDispatcherRepository = DispatcherAuthRepository(database.windDispatcherDao())

    companion object {
        @Volatile
        private var INSTANCE: AppContainer? = null

        fun get(context: Context): AppContainer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppContainer(context).also { INSTANCE = it }
            }
    }
}
