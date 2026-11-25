package ua.kpi.windpower.data.auth

import kotlinx.coroutines.flow.Flow

/**
 * Поточна сесія авторизованого диспетчера ВЕС, що зберігається локально.
 *
 * @property token Підписаний JWT-токен (`Header.Payload.Signature`).
 * @property role Роль користувача (`"ADMIN"` або `"USER"`), закодована у токені.
 * @property fullName ПІБ інженера.
 * @property email Корпоративний email / логін.
 * @property shift Закріплена робоча зміна.
 * @property expiresAtEpochMs Час завершення дії токена (UTC мілісекунди).
 */
data class AuthSession(
    val token: String,
    val role: String,
    val fullName: String,
    val email: String,
    val shift: String,
    val expiresAtEpochMs: Long
)

/**
 * Абстракція локального сховища токена автентифікації (Dependency Inversion).
 * Дозволяє використовувати Preferences DataStore на Android та In-Memory реалізацію
 * у модульних JVM-тестах.
 */
interface TokenStorage {
    val sessionFlow: Flow<AuthSession?>
    val tokenFlow: Flow<String?>
    suspend fun saveSession(session: AuthSession)
    suspend fun clearSession()
    suspend fun currentToken(): String?
}
