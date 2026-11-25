package ua.kpi.windpower.data.auth

import retrofit2.HttpException
import ua.kpi.windpower.data.network.NetworkErrorHandler
import ua.kpi.windpower.data.network.NetworkStatusProvider
import ua.kpi.windpower.data.network.OfflineNetworkException
import ua.kpi.windpower.data.remote.WindApiService
import ua.kpi.windpower.data.remote.dto.LoginRequestDto
import ua.kpi.windpower.data.remote.dto.ProtectedMessageDto
import ua.kpi.windpower.data.remote.dto.RegisterRequestDto
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto

/**
 * Репозиторій токен-базованої автентифікації та рольового доступу (ПЗ №11).
 *
 * Поєднує:
 * - віддалений REST API ([WindApiService]: `/login`, `/register`, `/protected`, `/admin-only`);
 * - локальне сховище токена ([TokenStorage] на базі Preferences DataStore);
 * - перевірку мережі ([NetworkStatusProvider]) і централізований [NetworkErrorHandler].
 */
class RemoteAuthRepository(
    private val api: WindApiService,
    private val tokenStorage: TokenStorage,
    private val networkProvider: NetworkStatusProvider,
    private val errorHandler: NetworkErrorHandler,
    private val clock: () -> Long = System::currentTimeMillis
) {

    val sessionFlow = tokenStorage.sessionFlow

    /** Виконує вхід через REST API та зберігає виданий JWT-токен у DataStore. */
    suspend fun login(username: String, password: String): Result<AuthSession> =
        executeGuarded {
            val response = api.login(LoginRequestDto(username.trim(), password))
            val session = AuthSession(
                token = response.token,
                role = response.role,
                fullName = response.fullName,
                email = username.trim(),
                shift = response.shift,
                expiresAtEpochMs = response.expiresAtEpochMs
            )
            tokenStorage.saveSession(session)
            session
        }

    /** Реєструє нового інженера через REST API, отримує JWT-токен і зберігає сесію. */
    suspend fun register(
        fullName: String,
        email: String,
        password: String,
        shift: String,
        role: String = "USER"
    ): Result<AuthSession> = executeGuarded {
        val response = api.register(
            RegisterRequestDto(
                username = email.trim(),
                password = password,
                fullName = fullName.trim(),
                shift = shift,
                role = role
            )
        )
        if (!response.isSuccessful) {
            throw HttpException(response)
        }
        val body = response.body() ?: throw IllegalStateException("Порожня відповідь реєстрації")
        val session = AuthSession(
            token = body.token,
            role = body.role,
            fullName = body.fullName,
            email = email.trim(),
            shift = body.shift,
            expiresAtEpochMs = body.expiresAtEpochMs
        )
        tokenStorage.saveSession(session)
        session
    }

    /**
     * Запитує захищений ресурс `/protected`.
     * Перед запитом перевіряє локальний термін дії токена (крім режиму [skipAuthHeader],
     * який навмисно демонструє відповідь сервера 401 Unauthorized без токена).
     */
    suspend fun loadProtectedData(skipAuthHeader: Boolean = false): Result<ProtectedMessageDto> =
        executeGuarded {
            if (!skipAuthHeader) {
                ensureLocalTokenNotExpired()
            }
            api.getProtectedData(skipAuth = if (skipAuthHeader) "true" else null)
        }

    /**
     * Запитує адміністративний ресурс `/admin-only`.
     * Для ролі `USER` сервер повертає HTTP 403 Forbidden, який обробляється через [NetworkErrorHandler].
     */
    suspend fun loadAdminStations(skipAuthHeader: Boolean = false): Result<List<RemoteWindStationDto>> =
        executeGuarded {
            if (!skipAuthHeader) {
                ensureLocalTokenNotExpired()
            }
            api.getAdminOnlyStations(skipAuth = if (skipAuthHeader) "true" else null)
        }

    /** Створює нову станцію ВЕС (дозволено лише ролі ADMIN). */
    suspend fun createStationAsAdmin(
        name: String,
        location: String,
        capacityMw: Double,
        turbinesCount: Int
    ): Result<RemoteWindStationDto> = executeGuarded {
        ensureLocalTokenNotExpired()
        val response = api.addStation(
            RemoteWindStationDto(
                id = 0,
                name = name.trim(),
                location = location.trim().ifBlank { "Україна" },
                capacityMw = capacityMw,
                turbinesCount = turbinesCount
            )
        )
        if (!response.isSuccessful) throw HttpException(response)
        response.body() ?: throw IllegalStateException("Порожня відповідь створення ВЕС")
    }

    /** Завершує зміну й очищує JWT-токен із DataStore. */
    suspend fun logout() {
        tokenStorage.clearSession()
    }

    /** Перевіряє валідність поточного збереженого токена за часом [clock]. */
    suspend fun isCurrentTokenExpired(): Boolean {
        val token = tokenStorage.currentToken() ?: return false
        return !JwtUtils.isTokenActive(token, clock())
    }

    private suspend fun ensureLocalTokenNotExpired() {
        val token = tokenStorage.currentToken()
        if (!token.isNullOrBlank() && !JwtUtils.isTokenActive(token, clock())) {
            throw TokenExpiredClientException()
        }
    }

    private suspend fun <T> executeGuarded(block: suspend () -> T): Result<T> {
        if (!networkProvider.isInternetAvailable()) {
            val msg = errorHandler.handleException(OfflineNetworkException())
            return Result.failure(IllegalStateException(msg))
        }
        return try {
            Result.success(block())
        } catch (e: TokenExpiredClientException) {
            errorHandler.handleException(e)
            Result.failure(e)
        } catch (e: Throwable) {
            val msg = errorHandler.handleException(e)
            Result.failure(IllegalStateException(msg, e))
        }
    }
}

/**
 * Локальний клієнтський виняток при виявленні протермінованого JWT-токена.
 */
class TokenExpiredClientException(
    message: String = "Термін дії токена завершився. Виконайте повторний вхід!"
) : IllegalStateException(message)
