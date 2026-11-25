package ua.kpi.windpower

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.kpi.windpower.data.Validators
import ua.kpi.windpower.data.auth.AuthSession
import ua.kpi.windpower.data.auth.JwtUtils
import ua.kpi.windpower.data.auth.RemoteAuthRepository
import ua.kpi.windpower.data.auth.TokenExpiredClientException
import ua.kpi.windpower.data.auth.TokenStorage
import ua.kpi.windpower.data.datasource.ForecastSortMode
import ua.kpi.windpower.data.datasource.InMemoryLocalWindDataSource
import ua.kpi.windpower.data.datasource.RemoteWindDataSource
import ua.kpi.windpower.data.network.ErrorCategory
import ua.kpi.windpower.data.network.InMemoryErrorLogSink
import ua.kpi.windpower.data.network.NetworkErrorHandler
import ua.kpi.windpower.data.network.NetworkHandledException
import ua.kpi.windpower.data.network.NetworkStatusProvider
import ua.kpi.windpower.data.remote.MockUserAccount
import ua.kpi.windpower.data.remote.MockWindBackendInterceptor
import ua.kpi.windpower.data.remote.RetrofitClient
import ua.kpi.windpower.data.remote.WindApiService
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.remote.dto.WindForecastDto
import ua.kpi.windpower.data.repository.DataOrigin
import ua.kpi.windpower.data.repository.WindForecastRepository

private class InMemoryTokenStorage : TokenStorage {
    private val state = MutableStateFlow<AuthSession?>(null)
    override val sessionFlow: Flow<AuthSession?> = state
    override val tokenFlow: Flow<String?> = state.map { it?.token }
    override suspend fun saveSession(session: AuthSession) {
        state.value = session
    }
    override suspend fun clearSession() {
        state.value = null
    }
    override suspend fun currentToken(): String? = state.value?.token
}

private class FakeNetworkProvider(var online: Boolean = true) : NetworkStatusProvider {
    override fun isInternetAvailable(): Boolean = online
}

/**
 * Модульні тести шарів ЛР №3 (ПЗ №9–12):
 * - JWT-токени, Base64Url, термін дії та підпис;
 * - Retrofit2 + Gson + OkHttp AuthInterceptor + RBAC (`ADMIN` / `USER`);
 * - Repository + Local/Remote DataSource, TTL-кеш, офлайн-fallback, CRUD, сортування й агрегація;
 * - NetworkErrorHandler, класифікація HTTP-кодів (400/401/403/404/500/503), retry та логування.
 */
class Lab3NetworkAndRepositoryTest {

    private var fakeClockMs = 1_700_000_000_000L
    private lateinit var tokenStorage: InMemoryTokenStorage
    private lateinit var networkProvider: FakeNetworkProvider
    private lateinit var logSink: InMemoryErrorLogSink
    private lateinit var errorHandler: NetworkErrorHandler
    private lateinit var mockBackend: MockWindBackendInterceptor
    private lateinit var api: WindApiService
    private lateinit var localDataSource: InMemoryLocalWindDataSource
    private lateinit var forecastRepository: WindForecastRepository
    private lateinit var authRepository: RemoteAuthRepository

    @Before
    fun setUp() {
        fakeClockMs = 1_700_000_000_000L
        tokenStorage = InMemoryTokenStorage()
        networkProvider = FakeNetworkProvider(online = true)
        logSink = InMemoryErrorLogSink()
        errorHandler = NetworkErrorHandler(logSink)
        mockBackend = MockWindBackendInterceptor(
            enabled = true,
            simulatedLatencyMs = 0L,
            clock = { fakeClockMs }
        )
        api = RetrofitClient.createApi(
            tokenStorage = tokenStorage,
            mockBackend = mockBackend
        )
        localDataSource = InMemoryLocalWindDataSource()
        val remoteDataSource = RemoteWindDataSource(api, networkProvider)
        forecastRepository = WindForecastRepository(
            local = localDataSource,
            remote = remoteDataSource,
            errorHandler = errorHandler,
            cacheTtlMs = 5 * 60 * 1000L,
            retryDelayMs = 0L,
            clock = { fakeClockMs }
        )
        authRepository = RemoteAuthRepository(
            api = api,
            tokenStorage = tokenStorage,
            networkProvider = networkProvider,
            errorHandler = errorHandler,
            clock = { fakeClockMs }
        )
    }

    // --- ПЗ №11: JWT та DataStore / AuthInterceptor / RBAC ---

    @Test
    fun jwtUtils_createsVerifiesAndDetectsExpiry() {
        val token = JwtUtils.createToken(
            subject = "engineer@windpower.kpi.ua",
            fullName = "Дмитро Гнилицький",
            role = "ADMIN",
            shift = "Зміна №1 (Денна)",
            ttlSeconds = 60L,
            nowEpochMs = fakeClockMs
        )
        val claims = JwtUtils.verifyAndDecode(token)
        assertNotNull(claims)
        assertEquals("engineer@windpower.kpi.ua", claims!!.sub)
        assertEquals("Дмитро Гнилицький", claims.name)
        assertEquals("ADMIN", claims.role)
        assertTrue(JwtUtils.isTokenActive(token, fakeClockMs + 30_000L))
        assertFalse(JwtUtils.isTokenActive(token, fakeClockMs + 61_000L))

        val tampered = token.dropLast(2) + "xx"
        assertNull(JwtUtils.verifyAndDecode(tampered))
    }

    @Test
    fun authFlow_adminCanAccessProtectedAndAdminEndpoints() = runBlocking {
        val login = authRepository.login("admin@windpower.kpi.ua", "Admin2026")
        assertTrue(login.isSuccess)
        assertEquals("ADMIN", login.getOrThrow().role)

        val protectedRes = authRepository.loadProtectedData()
        assertTrue(protectedRes.isSuccess)
        assertEquals("ADMIN", protectedRes.getOrThrow().issuedForRole)

        val adminRes = authRepository.loadAdminStations()
        assertTrue(adminRes.isSuccess)
        assertTrue(adminRes.getOrThrow().size >= 3)

        val created = authRepository.createStationAsAdmin("Сиваська ВЕС", "Херсонська обл.", 250.0, 64)
        assertTrue(created.isSuccess)
        assertEquals("Сиваська ВЕС", created.getOrThrow().name)
    }

    @Test
    fun authFlow_userRoleIsBlockedFromAdminEndpointWith403() = runBlocking {
        val login = authRepository.login("operator@windpower.kpi.ua", "User2026")
        assertTrue(login.isSuccess)
        assertEquals("USER", login.getOrThrow().role)

        assertTrue(authRepository.loadProtectedData().isSuccess)

        val adminAttempt = authRepository.loadAdminStations()
        assertTrue(adminAttempt.isFailure)
        assertTrue(logSink.readAll().any { it.httpCode == 403 && it.category == ErrorCategory.FORBIDDEN.name })
    }

    @Test
    fun authFlow_missingOrExpiredTokenReturns401AndLogsError() = runBlocking {
        // Запит без токена -> HTTP 401
        val unauthenticated = authRepository.loadProtectedData(skipAuthHeader = true)
        assertTrue(unauthenticated.isFailure)
        assertTrue(logSink.readAll().any { it.httpCode == 401 })

        // Протермінований токен виявляється на боці клієнта до виклику ресурсу
        val expiredToken = JwtUtils.createToken(
            subject = "admin@windpower.kpi.ua",
            fullName = "Дмитро Гнилицький",
            role = "ADMIN",
            shift = "Зміна №1",
            ttlSeconds = -60L,
            nowEpochMs = fakeClockMs
        )
        tokenStorage.saveSession(
            AuthSession(
                token = expiredToken,
                role = "ADMIN",
                fullName = "Дмитро Гнилицький",
                email = "admin@windpower.kpi.ua",
                shift = "Зміна №1",
                expiresAtEpochMs = fakeClockMs - 60_000L
            )
        )
        assertTrue(authRepository.isCurrentTokenExpired())
        val expiredAttempt = authRepository.loadProtectedData()
        assertTrue(expiredAttempt.isFailure)
        assertTrue(expiredAttempt.exceptionOrNull() is TokenExpiredClientException)
    }

    @Test
    fun authFlow_registerCreatesNewUserAndPersistsToken() = runBlocking {
        val reg = authRepository.register(
            fullName = "Ігор Савченко",
            email = "ihor@windpower.kpi.ua",
            password = "WindUser2026",
            shift = "Зміна №3 (Резервна)",
            role = "USER"
        )
        assertTrue(reg.isSuccess)
        assertNotNull(tokenStorage.currentToken())
        assertEquals("USER", reg.getOrThrow().role)
    }

    // --- ПЗ №9 та ПЗ №10: Repository, DataSource, TTL-кеш, Fallback, CRUD, Сортування ---

    @Test
    fun repository_cachesWithinTtl_andRefreshesAfterExpiryOrForce() = runBlocking {
        val first = forecastRepository.getForecasts().getOrThrow()
        assertEquals(DataOrigin.REMOTE_SYNCED, first.origin)
        assertTrue(first.items.isNotEmpty())

        // Повторний запит у межах 5 хв повертає свіжий кеш
        fakeClockMs += 60_000L
        val second = forecastRepository.getForecasts().getOrThrow()
        assertEquals(DataOrigin.CACHE_FRESH, second.origin)
        assertEquals(first.items.size, second.items.size)

        // Примусове оновлення звертається до сервера
        val forced = forecastRepository.getForecasts(forceRefresh = true).getOrThrow()
        assertEquals(DataOrigin.REMOTE_SYNCED, forced.origin)

        // Після завершення TTL термін актуальності кешу спливає, і дані оновлюються з сервера
        fakeClockMs += 6 * 60_000L
        val expiredTtl = forecastRepository.getForecasts().getOrThrow()
        assertEquals(DataOrigin.REMOTE_SYNCED, expiredTtl.origin)
    }

    @Test
    fun repository_fallsBackToLocalCacheWhenOffline() = runBlocking {
        // Попередньо наповнюємо кеш
        val seeded = forecastRepository.getForecasts().getOrThrow()
        assertEquals(DataOrigin.REMOTE_SYNCED, seeded.origin)

        // Переходимо в офлайн та скидаємо TTL
        networkProvider.online = false
        forecastRepository.invalidateCache()

        val fallback = forecastRepository.getForecasts().getOrThrow()
        assertEquals(DataOrigin.OFFLINE_CACHE_FALLBACK, fallback.origin)
        assertEquals(seeded.items.size, fallback.items.size)
        assertNotNull(fallback.warningMessage)

        // Якщо кеш порожній в офлайні — повертається помилка Offline
        localDataSource.clear()
        val emptyOffline = forecastRepository.getForecasts()
        assertTrue(emptyOffline.isFailure)
        val handled = emptyOffline.exceptionOrNull() as NetworkHandledException
        assertEquals(ErrorCategory.OFFLINE, handled.failure.category)
    }

    @Test
    fun repository_crudFilterSortAndBusinessSummaryWorkEndToEnd() = runBlocking {
        forecastRepository.getForecasts().getOrThrow()

        // Створення нового запису через POST /forecasts
        val added = forecastRepository.addForecast(
            WindForecastDto(
                id = 0,
                stationId = 1,
                date = "2026-11-30",
                windSpeedMps = 15.2,
                predictedPowerMw = 114.0,
                temperatureC = 6.0,
                turbulencePercent = 16,
                notes = "Штормовий тестовий прогноз"
            )
        ).getOrThrow()
        assertTrue(added.id > 0)

        // Оновлення запису через PUT /forecasts/{id}
        val updated = forecastRepository.updateForecast(
            added.copy(predictedPowerMw = 109.5, notes = "Оновлений прогноз")
        ).getOrThrow()
        assertEquals(109.5, updated.predictedPowerMw, 0.001)

        // Локальна фільтрація та сортування кешованого набору
        val filtered = forecastRepository.queryLocal(
            stationId = 1,
            query = "Оновлений",
            sortMode = ForecastSortMode.POWER_DESC
        )
        assertEquals(1, filtered.size)
        assertEquals(109.5, filtered.first().predictedPowerMw, 0.001)

        val sortedByWind = forecastRepository.queryLocal(
            stationId = 1,
            query = "",
            sortMode = ForecastSortMode.WIND_DESC
        )
        assertTrue(sortedByWind.first().windSpeedMps >= sortedByWind.last().windSpeedMps)

        // Отримання статистики та генерація нової серії прогнозів
        val stats = forecastRepository.getStationStats(1).getOrThrow()
        assertEquals(1, stats.stationId)
        assertTrue(stats.averagePowerMw > 0.0)

        val generated = forecastRepository.generateForecasts(stationId = 1, days = 2).getOrThrow()
        assertEquals(2, generated.size)

        // Видалення запису через DELETE /forecasts/{id}
        val deleted = forecastRepository.deleteForecast(added.id)
        assertTrue(deleted.isSuccess)
        assertNull(localDataSource.findById(added.id))
    }

    // --- ПЗ №12: Обробка помилок HTTP 400/404/500 та автоматичний Retry на 503 ---

    @Test
    fun errorHandler_doesNotRetryClientErrors400And404() = runBlocking {
        var retryCallbacks = 0
        val err404 = forecastRepository.simulateHttpError(404) { _, _, _ -> retryCallbacks++ }
        assertTrue(err404.isFailure)
        assertEquals(0, retryCallbacks)
        val ex = err404.exceptionOrNull() as NetworkHandledException
        assertEquals(ErrorCategory.NOT_FOUND, ex.failure.category)
        assertEquals(404, ex.failure.httpCode)

        val err400 = forecastRepository.simulateHttpError(400) { _, _, _ -> retryCallbacks++ }
        assertTrue(err400.isFailure)
        assertEquals(0, retryCallbacks)
        assertTrue(logSink.readAll().any { it.httpCode == 400 })
    }

    @Test
    fun errorHandler_retriesServer500AndRecoversOnFlaky503() = runBlocking {
        // 500 повторюється 3 рази (2 retry-колбеки) перед фінальною відмовою
        var retryCount500 = 0
        val err500 = forecastRepository.simulateHttpError(500) { _, _, _ -> retryCount500++ }
        assertTrue(err500.isFailure)
        assertEquals(2, retryCount500)
        assertTrue(logSink.readAll().any { it.httpCode == 500 && it.category == ErrorCategory.SERVER_HTTP.name })

        // Тимчасовий 503 на /forecast/today?flaky=true відновлюється на 3-й спробі
        mockBackend.resetFlakyCounter()
        var retryCount503 = 0
        val recovered = forecastRepository.getTodayForecastWithRetry(flaky = true) { _, _, _ ->
            retryCount503++
        }
        assertTrue(recovered.isSuccess)
        assertEquals(2, retryCount503)
        assertNotNull(recovered.getOrThrow().date)
    }

    @Test
    fun repository_computesStationStatsFromLocalCache_matchingRemoteFormula() = runBlocking {
        // Попередньо синхронізуємо дані в локальний кеш
        forecastRepository.getForecasts(stationId = 1, forceRefresh = true).getOrThrow()
        val remoteStats = forecastRepository.getStationStats(1).getOrThrow()
        val localStats = forecastRepository.computeStationStatsFromLocal(
            stationId = 1,
            stationCapacityMw = 114.0
        )

        assertEquals(remoteStats.stationId, localStats.stationId)
        assertEquals(remoteStats.averagePowerMw, localStats.averagePowerMw, 0.01)
        assertEquals(remoteStats.averageWindSpeedMps, localStats.averageWindSpeedMps, 0.01)
        assertEquals(remoteStats.averageTemperatureC, localStats.averageTemperatureC, 0.01)
        assertEquals(remoteStats.averageTurbulencePercent, localStats.averageTurbulencePercent, 0.01)
        assertEquals(remoteStats.efficiencyPercent, localStats.efficiencyPercent, 0.01)
    }

    @Test
    fun mockBackend_persistedUserLookup_restoresSessionAndBlocksDuplicateRegistration() = runBlocking {
        val persistedAccount = MockUserAccount(
            username = "restored@windpower.kpi.ua",
            passwordHash = Validators.sha256("PersistedPass1"),
            role = "ADMIN",
            fullName = "Марія Степаненко",
            shift = "Зміна №4 (Резервна)"
        )
        mockBackend.persistedUserLookup = { login ->
            if (login.equals(persistedAccount.username, ignoreCase = true)) persistedAccount else null
        }

        // Повторна реєстрація з тим самим email має бути відхилена (409 Conflict)
        val duplicateReg = authRepository.register(
            fullName = persistedAccount.fullName,
            email = persistedAccount.username,
            password = "PersistedPass1",
            shift = persistedAccount.shift,
            role = "USER"
        )
        assertTrue(duplicateReg.isFailure)

        // Вхід раніше збереженим обліковим записом після перезапуску успішний
        val login = authRepository.login(persistedAccount.username, "PersistedPass1").getOrThrow()
        assertEquals("ADMIN", login.role)
        assertEquals("Марія Степаненко", login.fullName)
    }

    @Test
    fun mockBackend_stationsDelegates_syncStationsReadAndCreate() = runBlocking {
        val backedStations = mutableListOf(
            RemoteWindStationDto(
                id = 77,
                name = "Тилігульська ВЕС (Room)",
                location = "Миколаївська обл.",
                capacityMw = 500.0,
                turbinesCount = 83
            )
        )
        mockBackend.stationsProvider = { backedStations.toList() }
        mockBackend.onStationCreatedFromApi = { incoming ->
            val persisted = incoming.copy(id = 101)
            backedStations.add(persisted)
            persisted
        }

        val login = authRepository.login("admin@windpower.kpi.ua", "Admin2026")
        assertTrue(login.isSuccess)

        val adminStations = authRepository.loadAdminStations().getOrThrow()
        assertEquals(1, adminStations.size)
        assertEquals(77, adminStations.first().id)
        assertEquals("Тилігульська ВЕС (Room)", adminStations.first().name)

        // Генерація прогнозів підхоплює станцію з делегата Room БД
        val forecasts = forecastRepository.getForecasts(stationId = 77, forceRefresh = true).getOrThrow()
        assertTrue(forecasts.items.isNotEmpty())
        assertEquals(77, forecasts.items.first().stationId)

        // Створення станції через адміністративний API транслюється в Room-делегат
        val created = authRepository.createStationAsAdmin(
            name = "Дністровська ВЕС-Тест",
            location = "Одеська обл.",
            capacityMw = 120.0,
            turbinesCount = 20
        ).getOrThrow()
        assertEquals(101, created.id)
        assertEquals(2, backedStations.size)
        assertEquals("Дністровська ВЕС-Тест", backedStations.last().name)
    }
}
