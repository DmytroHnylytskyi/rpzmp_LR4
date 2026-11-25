package ua.kpi.windpower.data.remote

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import ua.kpi.windpower.data.Validators
import ua.kpi.windpower.data.auth.JwtClaims
import ua.kpi.windpower.data.auth.JwtUtils
import ua.kpi.windpower.data.remote.dto.ApiErrorMessageDto
import ua.kpi.windpower.data.remote.dto.LoginRequestDto
import ua.kpi.windpower.data.remote.dto.LoginResponseDto
import ua.kpi.windpower.data.remote.dto.ProtectedMessageDto
import ua.kpi.windpower.data.remote.dto.RegisterRequestDto
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.remote.dto.WindForecastDto
import ua.kpi.windpower.data.remote.dto.WindForecastStatsDto
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Модель облікового запису користувача на боці серверної імітації REST API.
 */
data class MockUserAccount(
    val username: String,
    val passwordHash: String,
    val role: String,
    val fullName: String,
    val shift: String
)

/**
 * Автономний модуль імітації REST-сервера на рівні [Interceptor] OkHttp.
 *
 * Архітектурне обґрунтування використання OkHttp Interceptor:
 * - Повний клієнтський мережевий стек (Retrofit2 → Gson → OkHttpClient → AuthInterceptor)
 *   функціонує без жодних спрощень або обходу конвеєра HTTP-запитів.
 * - Застосунок стабільно виконується на фізичному пристрої або емуляторі в Android Studio
 *   без потреби розгортати зовнішній сервер за адресою localhost:5156.
 * - За наявності зовнішнього сервера перемикач [enabled] переводиться у false,
 *   після чого запити спрямовуються на `http://10.0.2.2:5156/`.
 * - Зовнішній делегат [persistedUserLookup] дозволяє розпізнавати облікові записи,
 *   збережені у локальній таблиці `wind_dispatchers`, навіть після перезапуску застосунку.
 * - Делегати [stationsProvider] та [onStationCreatedFromApi] синхронізують перелік ВЕС
 *   із локальною базою даних Room як єдиним джерелом даних.
 */
class MockWindBackendInterceptor(
    @Volatile var enabled: Boolean = true,
    var simulatedLatencyMs: Long = 160L,
    private val clock: () -> Long = System::currentTimeMillis,
    @Volatile var persistedUserLookup: ((String) -> MockUserAccount?)? = null,
    @Volatile var stationsProvider: (() -> List<RemoteWindStationDto>)? = null,
    @Volatile var onStationCreatedFromApi: ((RemoteWindStationDto) -> RemoteWindStationDto)? = null
) : Interceptor {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val lock = Any()

    private val users = mutableListOf(
        MockUserAccount(
            username = "admin@windpower.kpi.ua",
            passwordHash = Validators.sha256("Admin2026"),
            role = "ADMIN",
            fullName = "Гнилицький Дмитро (Головний диспетчер)",
            shift = "Зміна №1 (Денна)"
        ),
        MockUserAccount(
            username = "operator@windpower.kpi.ua",
            passwordHash = Validators.sha256("User2026"),
            role = "USER",
            fullName = "Олена Ковальчук (Черговий оператор)",
            shift = "Зміна №2 (Нічна)"
        )
    )

    private val stations = mutableListOf(
        RemoteWindStationDto(1, "Тилігульська ВЕС", "Миколаївська обл.", 114.0, 19),
        RemoteWindStationDto(2, "Ботієвська ВЕС", "Запорізька обл.", 200.0, 65),
        RemoteWindStationDto(3, "Приморська ВЕС", "Запорізька обл.", 200.0, 52)
    )

    private val forecasts = mutableListOf(
        WindForecastDto(1, 1, "2026-11-23", 10.4, 74.2, 7.5, 11, "Стійкий морський бриз (MPPT)"),
        WindForecastDto(2, 1, "2026-11-24", 12.6, 114.0, 6.2, 14, "Номінальний вітер, Pitch-стабілізація"),
        WindForecastDto(3, 1, "2026-11-25", 7.8, 31.3, 8.0, 9, "Помірне навантаження мережі"),
        WindForecastDto(4, 2, "2026-11-23", 11.2, 162.5, 9.1, 12, "Оптимальний режим Приазов'я"),
        WindForecastDto(5, 2, "2026-11-24", 14.1, 200.0, 8.4, 18, "Повна номінальна віддача"),
        WindForecastDto(6, 3, "2026-11-23", 8.9, 81.6, 8.8, 10, "Штатний добовий графік")
    )

    private var nextForecastId = 7
    private var nextStationId = 4
    private var flakyAttemptCounter = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!enabled) {
            return chain.proceed(request)
        }

        if (simulatedLatencyMs > 0L) {
            try {
                Thread.sleep(simulatedLatencyMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val path = request.url.encodedPath
        val method = request.method.uppercase()

        return synchronized(lock) {
            routeRequest(request, method, path)
        }
    }

    private fun routeRequest(request: Request, method: String, path: String): Response {
        // --- 1. Автентифікація (ПЗ №11) ---
        if (method == "POST" && path == "/login") {
            return handleLogin(request)
        }
        if (method == "POST" && path == "/register") {
            return handleRegister(request)
        }
        if (method == "GET" && path == "/protected") {
            val claims = verifyBearer(request) ?: return jsonError(
                request, 401, "Токен відсутній, недійсний або термін його дії вичерпано"
            )
            val payload = ProtectedMessageDto(
                message = "Захищений SCADA-канал відкрито для ${claims.name} (роль ${claims.role})",
                issuedForRole = claims.role,
                serverTimestampMs = clock()
            )
            return jsonSuccess(request, payload)
        }
        if (method == "GET" && path == "/admin-only") {
            val claims = verifyBearer(request) ?: return jsonError(
                request, 401, "Потрібна авторизація: токен відсутній або протермінований"
            )
            if (!claims.role.equals("ADMIN", ignoreCase = true)) {
                return jsonError(
                    request,
                    403,
                    "Доступ заборонено: роль ${claims.role} не має адміністративних прав SCADA"
                )
            }
            return jsonSuccess(request, currentStations())
        }

        // --- 2. Оперативний прогноз та симуляція помилок (ПЗ №12) ---
        if (method == "GET" && path == "/forecast/today") {
            val flaky = request.url.queryParameter("flaky") == "true"
            if (flaky) {
                flakyAttemptCounter++
                // Перші 2 спроби імітують тимчасовий збій 503, 3-тя відновлюється автоматично
                if (flakyAttemptCounter % 3 != 0) {
                    return jsonError(
                        request,
                        503,
                        "Метеошлюз SCADA тимчасово недоступний (спроба #$flakyAttemptCounter)"
                    )
                }
            }
            val today = forecasts.firstOrNull() ?: WindForecastDto(
                id = 100,
                stationId = 1,
                date = "2026-11-23",
                windSpeedMps = 10.8,
                predictedPowerMw = 83.2,
                temperatureC = 7.4,
                turbulencePercent = 11,
                notes = "Оперативний метеозріз"
            )
            return jsonSuccess(request, today)
        }

        val errorMatch = Regex("^/forecast/error/(\\d+)$").matchEntire(path)
        if (method == "GET" && errorMatch != null) {
            val code = errorMatch.groupValues[1].toIntOrNull() ?: 500
            return jsonError(request, code, "Симульована відповідь сервера з кодом $code")
        }

        // --- 3. Станції ВЕС (ПЗ №10) ---
        if (method == "GET" && path == "/stations") {
            return jsonSuccess(request, currentStations())
        }
        val stationByIdMatch = Regex("^/stations/(\\d+)$").matchEntire(path)
        if (method == "GET" && stationByIdMatch != null) {
            val id = stationByIdMatch.groupValues[1].toInt()
            val found = currentStations().firstOrNull { it.id == id }
                ?: return jsonError(request, 404, "Станцію #$id не знайдено")
            return jsonSuccess(request, found)
        }
        if (method == "POST" && path == "/stations") {
            val claims = verifyBearer(request) ?: return jsonError(
                request, 401, "Для додавання станції потрібна авторизація"
            )
            if (!claims.role.equals("ADMIN", ignoreCase = true)) {
                return jsonError(request, 403, "Лише ADMIN може реєструвати нові ВЕС на сервері")
            }
            val body = readBody(request)
            val dto = gson.fromJson(body, RemoteWindStationDto::class.java)
            if (dto == null || dto.name.isBlank() || dto.capacityMw <= 0.0) {
                return jsonError(request, 400, "Некоректні параметри нової ВЕС")
            }
            val created = if (onStationCreatedFromApi != null) {
                try {
                    onStationCreatedFromApi!!.invoke(dto)
                } catch (e: IllegalArgumentException) {
                    return jsonError(request, 400, e.message ?: "Станція з такою назвою вже існує")
                }
            } else {
                val item = dto.copy(id = nextStationId++)
                stations.add(item)
                item
            }
            return jsonSuccess(request, created, code = 201)
        }

        // --- 4. Прогнози та статистика (ПЗ №9, ПЗ №10) ---
        if (method == "GET" && path == "/forecasts") {
            return jsonSuccess(request, forecasts.toList())
        }
        if (method == "POST" && path == "/forecasts") {
            val dto = gson.fromJson(readBody(request), WindForecastDto::class.java)
                ?: return jsonError(request, 400, "Порожнє або некоректне тіло прогнозу")
            if (dto.date.isBlank() || dto.windSpeedMps < 0.0 || dto.predictedPowerMw < 0.0) {
                return jsonError(request, 400, "Невалідні дані прогнозу вітрової генерації")
            }
            val created = dto.copy(id = nextForecastId++)
            forecasts.add(created)
            return jsonSuccess(request, created, code = 201)
        }

        val forecastIdMatch = Regex("^/forecasts/(\\d+)$").matchEntire(path)
        if (forecastIdMatch != null) {
            val id = forecastIdMatch.groupValues[1].toInt()
            if (method == "PUT") {
                val dto = gson.fromJson(readBody(request), WindForecastDto::class.java)
                    ?: return jsonError(request, 400, "Некоректні дані для оновлення")
                val index = forecasts.indexOfFirst { it.id == id }
                if (index < 0) return jsonError(request, 404, "Прогноз #$id не знайдено")
                val updated = dto.copy(id = id)
                forecasts[index] = updated
                return jsonSuccess(request, updated)
            }
            if (method == "DELETE") {
                val removed = forecasts.removeAll { it.id == id }
                if (!removed) return jsonError(request, 404, "Прогноз #$id не знайдено")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body("".toResponseBody(null))
                    .build()
            }
        }

        val stationForecastsMatch = Regex("^/stations/(\\d+)/forecasts$").matchEntire(path)
        if (method == "GET" && stationForecastsMatch != null) {
            val stationId = stationForecastsMatch.groupValues[1].toInt()
            val station = currentStations().firstOrNull { it.id == stationId }
                ?: return jsonError(request, 404, "Станцію #$stationId не знайдено")
            if (forecasts.none { it.stationId == stationId }) {
                appendGeneratedForecasts(station, days = 3)
            }
            return jsonSuccess(request, forecasts.filter { it.stationId == stationId })
        }

        val stationStatsMatch = Regex("^/stations/(\\d+)/forecasts/stats$").matchEntire(path)
        if (method == "GET" && stationStatsMatch != null) {
            val stationId = stationStatsMatch.groupValues[1].toInt()
            val station = currentStations().firstOrNull { it.id == stationId }
                ?: return jsonError(request, 404, "Станцію #$stationId не знайдено")
            if (forecasts.none { it.stationId == stationId }) {
                appendGeneratedForecasts(station, days = 3)
            }
            val list = forecasts.filter { it.stationId == stationId }
            val avgPower = if (list.isEmpty()) 0.0 else list.map { it.predictedPowerMw }.average()
            val avgWind = if (list.isEmpty()) 0.0 else list.map { it.windSpeedMps }.average()
            val avgTemp = if (list.isEmpty()) 0.0 else list.map { it.temperatureC }.average()
            val avgTurb = if (list.isEmpty()) 0.0 else list.map { it.turbulencePercent }.average()
            val eff = if (station.capacityMw <= 0.0) 0.0 else (avgPower / station.capacityMw) * 100.0
            val stats = WindForecastStatsDto(
                stationId = stationId,
                averagePowerMw = round2(avgPower),
                averageWindSpeedMps = round2(avgWind),
                averageTemperatureC = round2(avgTemp),
                averageTurbulencePercent = round2(avgTurb),
                efficiencyPercent = round2(eff)
            )
            return jsonSuccess(request, stats)
        }

        val generateMatch = Regex("^/stations/(\\d+)/forecasts/generate/(\\d+)$").matchEntire(path)
        if (method == "POST" && generateMatch != null) {
            val stationId = generateMatch.groupValues[1].toInt()
            val days = generateMatch.groupValues[2].toInt().coerceIn(1, 7)
            val station = currentStations().firstOrNull { it.id == stationId }
                ?: return jsonError(request, 404, "Станцію #$stationId не знайдено")
            return jsonSuccess(request, appendGeneratedForecasts(station, days))
        }

        return jsonError(request, 404, "Ендпоінт $method $path не знайдено")
    }

    /**
     * Формує та додає до кешу [days] добових метеопрогнозів для станції [station].
     */
    private fun appendGeneratedForecasts(
        station: RemoteWindStationDto,
        days: Int
    ): List<WindForecastDto> {
        val generated = mutableListOf<WindForecastDto>()
        val baseOffset = forecasts.count { it.stationId == station.id }
        for (d in 1..days) {
            val wind = round2((4.5 + ((baseOffset + d) * 2.7) % 19.0))
            val power = estimateStationPower(station.capacityMw, wind)
            val temp = round2(5.0 + ((d * 1.9) % 11.0))
            val turb = (8 + (d * 3)) % 24
            val dayNum = (25 + baseOffset + d).coerceAtMost(30)
            val item = WindForecastDto(
                id = nextForecastId++,
                stationId = station.id,
                date = "2026-11-${dayNum.toString().padStart(2, '0')}",
                windSpeedMps = wind,
                predictedPowerMw = power,
                temperatureC = temp,
                turbulencePercent = turb,
                notes = when {
                    wind < 3.0 -> "Нижче Cut-in (очікування)"
                    wind > 25.0 -> "Шторм Cut-out (гальмування)"
                    wind >= 12.0 -> "Номінальний режим Pitch"
                    else -> "Автопрогноз моделі MPPT"
                }
            )
            forecasts.add(item)
            generated.add(item)
        }
        return generated
    }

    private fun handleLogin(request: Request): Response {
        val dto = try {
            gson.fromJson(readBody(request), LoginRequestDto::class.java)
        } catch (_: Exception) {
            null
        } ?: return jsonError(request, 400, "Некоректний формат запиту входу")

        val login = dto.username.trim()
        if (login.isBlank() || dto.password.isBlank()) {
            return jsonError(request, 400, "Логін та пароль не можуть бути порожніми")
        }

        val user = users.firstOrNull { it.username.equals(login, ignoreCase = true) }
            ?: persistedUserLookup?.invoke(login)
            ?: return jsonError(request, 401, "Користувача «$login» не знайдено")

        if (user.passwordHash != Validators.sha256(dto.password)) {
            return jsonError(request, 401, "Невірний пароль доступу")
        }

        val now = clock()
        val ttlSeconds = 3600L
        val token = JwtUtils.createToken(
            subject = user.username,
            fullName = user.fullName,
            role = user.role,
            shift = user.shift,
            ttlSeconds = ttlSeconds,
            nowEpochMs = now
        )
        return jsonSuccess(
            request,
            LoginResponseDto(
                token = token,
                role = user.role,
                fullName = user.fullName,
                shift = user.shift,
                expiresAtEpochMs = now + ttlSeconds * 1000L
            )
        )
    }

    private fun handleRegister(request: Request): Response {
        val dto = try {
            gson.fromJson(readBody(request), RegisterRequestDto::class.java)
        } catch (_: Exception) {
            null
        } ?: return jsonError(request, 400, "Некоректний формат даних реєстрації")

        val email = dto.username.trim()
        if (email.isBlank() || dto.password.isBlank() || dto.fullName.isBlank()) {
            return jsonError(request, 400, "Заповніть усі обов'язкові поля реєстрації")
        }
        val alreadyExists = users.any { it.username.equals(email, ignoreCase = true) } ||
            persistedUserLookup?.invoke(email) != null
        if (alreadyExists) {
            return jsonError(request, 400, "Користувач із таким Email уже зареєстрований")
        }

        val normalizedRole = if (dto.role.equals("ADMIN", ignoreCase = true)) "ADMIN" else "USER"
        val user = MockUserAccount(
            username = email,
            passwordHash = Validators.sha256(dto.password),
            role = normalizedRole,
            fullName = dto.fullName.trim(),
            shift = dto.shift.ifBlank { "Зміна №1 (Денна)" }
        )
        users.add(user)

        val now = clock()
        val ttlSeconds = 3600L
        val token = JwtUtils.createToken(
            subject = user.username,
            fullName = user.fullName,
            role = user.role,
            shift = user.shift,
            ttlSeconds = ttlSeconds,
            nowEpochMs = now
        )
        return jsonSuccess(
            request,
            LoginResponseDto(
                token = token,
                role = user.role,
                fullName = user.fullName,
                shift = user.shift,
                expiresAtEpochMs = now + ttlSeconds * 1000L
            ),
            code = 201
        )
    }

    private fun verifyBearer(request: Request): JwtClaims? {
        val header = request.header("Authorization") ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        val token = header.removePrefix("Bearer ").trim()
        if (!JwtUtils.isTokenActive(token, clock())) return null
        return JwtUtils.verifyAndDecode(token)
    }

    private fun estimateStationPower(capacityMw: Double, windMps: Double): Double {
        val mw = when {
            windMps < 3.0 || windMps > 25.0 -> 0.0
            windMps >= 12.0 -> capacityMw
            else -> capacityMw * (windMps / 12.0).pow(3)
        }
        return round2(mw)
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0

    private fun currentStations(): List<RemoteWindStationDto> =
        stationsProvider?.invoke() ?: stations.toList()

    private fun readBody(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun jsonSuccess(request: Request, bodyObj: Any, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 201) "Created" else "OK")
            .body(gson.toJson(bodyObj).toResponseBody(jsonMediaType))
            .build()

    private fun jsonError(request: Request, code: Int, message: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(gson.toJson(ApiErrorMessageDto(message)).toResponseBody(jsonMediaType))
            .build()

    fun resetFlakyCounter() {
        synchronized(lock) { flakyAttemptCounter = 0 }
    }
}
