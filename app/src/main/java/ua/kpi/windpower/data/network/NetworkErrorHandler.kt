package ua.kpi.windpower.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Класифікація категорій мережевих помилок для централізованої обробки (ПЗ №12).
 */
enum class ErrorCategory {
    OFFLINE,
    CLIENT_HTTP,
    AUTH_UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    SERVER_HTTP,
    TIMEOUT,
    UNKNOWN
}

/**
 * Структурований результат класифікації винятку мережевого шару.
 *
 * @property category Категорія збою (клієнтська, серверна, мережева або тимчасова).
 * @property httpCode Код відповіді HTTP (якщо це [HttpException]), інакше null.
 * @property userMessage Зрозуміле повідомлення українською мовою для відображення у UI.
 * @property isRetryable Чи доцільно автоматично повторювати запит (500, 503, таймаут).
 */
data class NetworkFailure(
    val category: ErrorCategory,
    val httpCode: Int?,
    val userMessage: String,
    val isRetryable: Boolean
)

/**
 * Обгорнутий виняток, що несе в собі класифіковану помилку [failure]
 * та готове повідомлення для користувача.
 */
class NetworkHandledException(
    val failure: NetworkFailure,
    cause: Throwable? = null
) : Exception(failure.userMessage, cause)

/**
 * Централізований обробник мережевих помилок і механізм повторних спроб (ПЗ №12).
 *
 * Відокремлює класифікацію HTTP-кодів, логування у журнал/файл та retry-політику
 * від ViewModel і Compose UI.
 */
class NetworkErrorHandler(
    private val logSink: ErrorLogSink? = null
) {

    /**
     * Визначає тип винятку, формує зрозуміле повідомлення для користувача
     * та вказує, чи підлягає операція автоматичному повторенню.
     */
    fun classify(throwable: Throwable): NetworkFailure = when (throwable) {
        is NetworkHandledException -> throwable.failure
        is OfflineNetworkException -> NetworkFailure(
            category = ErrorCategory.OFFLINE,
            httpCode = null,
            userMessage = "Немає підключення до Інтернету",
            isRetryable = false
        )
        is HttpException -> classifyHttpCode(throwable.code())
        is SocketTimeoutException -> NetworkFailure(
            category = ErrorCategory.TIMEOUT,
            httpCode = null,
            userMessage = "Перевищено час очікування відповіді від сервера (таймаут)",
            isRetryable = true
        )
        is UnknownHostException -> NetworkFailure(
            category = ErrorCategory.OFFLINE,
            httpCode = null,
            userMessage = "Сервер недоступний: перевірте мережеве з'єднання",
            isRetryable = false
        )
        is IOException -> NetworkFailure(
            category = ErrorCategory.SERVER_HTTP,
            httpCode = null,
            userMessage = "Помилка мережевого каналу: ${throwable.localizedMessage ?: "збій передачі"}",
            isRetryable = true
        )
        else -> NetworkFailure(
            category = ErrorCategory.UNKNOWN,
            httpCode = null,
            userMessage = "Помилка виконання запиту: ${throwable.localizedMessage ?: "невідома помилка"}",
            isRetryable = false
        )
    }

    /**
     * Обробляє виняток, реєструє його у журналі [ErrorLogSink] і повертає текст для UI.
     */
    fun handleException(throwable: Throwable): String {
        val failure = classify(throwable)
        logSink?.record(
            category = failure.category.name,
            httpCode = failure.httpCode,
            message = failure.userMessage
        )
        return failure.userMessage
    }

    /**
     * Виконує мережевий запит із автоматичними повторними спробами (до [maxRetries] разів)
     * для тимчасових помилок сервера (HTTP 500, 502, 503, 504) і мережевих таймаутів.
     *
     * Критичні клієнтські помилки (400, 401, 403, 404, Offline) не повторюються даремно
     * й одразу повертаються користувачеві з відповідним поясненням.
     */
    suspend fun <T> executeWithRetry(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
        onRetryAttempt: (attempt: Int, maxAttempts: Int, reason: String) -> Unit = { _, _, _ -> },
        block: suspend () -> T
    ): Result<T> {
        var attempt = 0
        var lastError: Throwable? = null
        var lastFailure: NetworkFailure? = null

        while (attempt < maxRetries) {
            attempt++
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                val failure = classify(e)
                lastFailure = failure

                if (failure.isRetryable && attempt < maxRetries) {
                    logSink?.record(
                        category = "${failure.category.name}_RETRY",
                        httpCode = failure.httpCode,
                        message = "Спроба $attempt/$maxRetries не вдалася: ${failure.userMessage}"
                    )
                    onRetryAttempt(attempt, maxRetries, failure.userMessage)
                    if (retryDelayMs > 0L) delay(retryDelayMs)
                    continue
                }
                break
            }
        }

        val finalFailure = lastFailure ?: NetworkFailure(
            category = ErrorCategory.UNKNOWN,
            httpCode = null,
            userMessage = "Невідома мережева помилка",
            isRetryable = false
        )
        logSink?.record(
            category = finalFailure.category.name,
            httpCode = finalFailure.httpCode,
            message = finalFailure.userMessage
        )
        return Result.failure(NetworkHandledException(finalFailure, lastError))
    }

    private fun classifyHttpCode(code: Int): NetworkFailure = when (code) {
        400 -> NetworkFailure(
            category = ErrorCategory.CLIENT_HTTP,
            httpCode = 400,
            userMessage = "Некоректний запит (HTTP 400): перевірте введені дані",
            isRetryable = false
        )
        401 -> NetworkFailure(
            category = ErrorCategory.AUTH_UNAUTHORIZED,
            httpCode = 401,
            userMessage = "Не авторизовано або токен протерміновано (HTTP 401). Виконайте вхід",
            isRetryable = false
        )
        403 -> NetworkFailure(
            category = ErrorCategory.FORBIDDEN,
            httpCode = 403,
            userMessage = "Доступ заборонено (HTTP 403): недостатньо прав ролі",
            isRetryable = false
        )
        404 -> NetworkFailure(
            category = ErrorCategory.NOT_FOUND,
            httpCode = 404,
            userMessage = "Прогноз або ресурс не знайдено на сервері (HTTP 404)",
            isRetryable = false
        )
        500 -> NetworkFailure(
            category = ErrorCategory.SERVER_HTTP,
            httpCode = 500,
            userMessage = "Внутрішня помилка сервера (HTTP 500), спробуйте пізніше",
            isRetryable = true
        )
        502, 503, 504 -> NetworkFailure(
            category = ErrorCategory.SERVER_HTTP,
            httpCode = code,
            userMessage = "Шлюз телеметрії тимчасово перевантажений (HTTP $code)",
            isRetryable = true
        )
        else -> NetworkFailure(
            category = if (code in 400..499) ErrorCategory.CLIENT_HTTP else ErrorCategory.SERVER_HTTP,
            httpCode = code,
            userMessage = "Помилка сервера (HTTP $code)",
            isRetryable = code >= 500
        )
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_RETRY_DELAY_MS = 1000L
    }
}
