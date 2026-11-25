package ua.kpi.windpower.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore by preferencesDataStore(name = "aerogrid_auth_prefs")

/**
 * Локальне сховище JWT-токена та метаданих сесії на базі Jetpack Preferences DataStore (ПЗ №11).
 *
 * Забезпечує асинхронне збереження токена між перезапусками застосунку та
 * реактивне сповіщення ViewModel і OkHttp Interceptor про зміни стану авторизації.
 */
class AuthDataStore(context: Context) : TokenStorage {

    private val dataStore = context.applicationContext.authDataStore

    override val sessionFlow: Flow<AuthSession?> = dataStore.data.map { prefs ->
        val token = prefs[KEY_TOKEN] ?: return@map null
        AuthSession(
            token = token,
            role = prefs[KEY_ROLE] ?: "USER",
            fullName = prefs[KEY_FULL_NAME] ?: "Диспетчер ВЕС",
            email = prefs[KEY_EMAIL] ?: "",
            shift = prefs[KEY_SHIFT] ?: "Зміна №1 (Денна)",
            expiresAtEpochMs = prefs[KEY_EXPIRES_AT] ?: 0L
        )
    }

    override val tokenFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_TOKEN]
    }

    override suspend fun saveSession(session: AuthSession) {
        dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = session.token
            prefs[KEY_ROLE] = session.role
            prefs[KEY_FULL_NAME] = session.fullName
            prefs[KEY_EMAIL] = session.email
            prefs[KEY_SHIFT] = session.shift
            prefs[KEY_EXPIRES_AT] = session.expiresAtEpochMs
        }
    }

    override suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_ROLE)
            prefs.remove(KEY_FULL_NAME)
            prefs.remove(KEY_EMAIL)
            prefs.remove(KEY_SHIFT)
            prefs.remove(KEY_EXPIRES_AT)
        }
    }

    override suspend fun currentToken(): String? = tokenFlow.first()

    /**
     * Демонстраційне протермінування збереженого токена для перевірки сценарію
     * завершення терміну дії JWT (очікується реакція клієнта та HTTP 401).
     */
    suspend fun expireCurrentTokenForDemo() {
        val current = sessionFlow.first() ?: return
        val pastMs = System.currentTimeMillis() - 120_000L
        val expiredJwt = JwtUtils.createToken(
            subject = current.email,
            fullName = current.fullName,
            role = current.role,
            shift = current.shift,
            ttlSeconds = -120L
        )
        saveSession(
            current.copy(
                token = expiredJwt,
                expiresAtEpochMs = pastMs
            )
        )
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("jwt_token")
        val KEY_ROLE = stringPreferencesKey("user_role")
        val KEY_FULL_NAME = stringPreferencesKey("user_full_name")
        val KEY_EMAIL = stringPreferencesKey("user_email")
        val KEY_SHIFT = stringPreferencesKey("user_shift")
        val KEY_EXPIRES_AT = longPreferencesKey("token_expires_at_ms")
    }
}
