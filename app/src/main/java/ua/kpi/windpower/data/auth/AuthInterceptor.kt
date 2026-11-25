package ua.kpi.windpower.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp-перехоплювач для автоматичної авторизації всіх HTTP-запитів (ПЗ №11).
 *
 * Принцип роботи:
 * 1. Перед кожним запитом читає поточний JWT-токен із [TokenStorage].
 * 2. Якщо запит містить службовий заголовок `X-Skip-Auth` (діагностичний режим тесту
 *    доступу без токена), заголовок знімається і `Authorization` не додається.
 * 3. Інакше до запиту автоматично додається заголовок `Authorization: Bearer <token>`.
 */
class AuthInterceptor(
    private val tokenStorage: TokenStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val skipAuth = original.header(HEADER_SKIP_AUTH) != null

        val builder = original.newBuilder().removeHeader(HEADER_SKIP_AUTH)
        if (!skipAuth) {
            val token = runBlocking { tokenStorage.currentToken() }
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
        }
        return chain.proceed(builder.build())
    }

    companion object {
        const val HEADER_SKIP_AUTH = "X-Skip-Auth"
    }
}
