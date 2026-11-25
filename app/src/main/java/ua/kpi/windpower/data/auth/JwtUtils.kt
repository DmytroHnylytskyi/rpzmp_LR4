package ua.kpi.windpower.data.auth

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * Задекларовані твердження (Payload) JWT-токена системи AeroGrid.
 *
 * Використовується як на боці клієнта (розшифрування ролі користувача для
 * адаптації інтерфейсу та перевірка терміну дії токена), так і у вбудованому
 * Mock API для перевірки прав доступу до захищених ендпоінтів.
 */
data class JwtClaims(
    val sub: String,
    val name: String,
    val role: String,
    val shift: String,
    val iat: Long,
    val exp: Long
)

/**
 * Утиліта генерації, декодування та валідації JWT (JSON Web Token).
 *
 * Реалізує стандартну тричастинну структуру `Header.Payload.Signature` з чистим
 * Kotlin-кодуванням Base64Url (без залежності від Android API 26+ `java.util.Base64`
 * та Android Framework, що дозволяє виконувати модульні JVM-тести).
 */
object JwtUtils {

    private const val JWT_SECRET = "AeroGrid_KPI_SCADA_2026_Secret"
    private val gson = Gson()
    private const val BASE64_URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * Формує підписаний JWT-токен із зашитими даними користувача та терміном дії.
     *
     * @param subject Логін або корпоративний email диспетчера.
     * @param fullName ПІБ інженера.
     * @param role Роль доступу (`"ADMIN"` або `"USER"`).
     * @param shift Закріплена робоча зміна.
     * @param ttlSeconds Час життя токена у секундах (може бути від'ємним для тесту протермінування).
     * @param nowEpochMs Поточний час у мілісекундах.
     */
    fun createToken(
        subject: String,
        fullName: String,
        role: String,
        shift: String,
        ttlSeconds: Long = 3600L,
        nowEpochMs: Long = System.currentTimeMillis()
    ): String {
        val iatSec = nowEpochMs / 1000L
        val expSec = iatSec + ttlSeconds

        val headerJson = """{"alg":"HS256","typ":"JWT"}"""
        val claims = JwtClaims(
            sub = subject,
            name = fullName,
            role = role.uppercase(),
            shift = shift,
            iat = iatSec,
            exp = expSec
        )
        val payloadJson = gson.toJson(claims)

        val encodedHeader = encodeBase64Url(headerJson.toByteArray(Charsets.UTF_8))
        val encodedPayload = encodeBase64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val unsignedToken = "$encodedHeader.$encodedPayload"
        val signature = sign(unsignedToken)

        return "$unsignedToken.$signature"
    }

    /**
     * Перевіряє підпис токена та декодує Payload.
     * Повертає null, якщо формат токена пошкоджений або підпис не збігається.
     */
    fun verifyAndDecode(token: String): JwtClaims? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val unsigned = "${parts[0]}.${parts[1]}"
        if (sign(unsigned) != parts[2]) return null

        return decodePayloadOnly(parts[1])
    }

    /**
     * Декодує корисне навантаження токена без перевірки підпису (для діагностики в UI).
     */
    fun decodePayload(token: String): JwtClaims? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return decodePayloadOnly(parts[1])
    }

    /**
     * Перевіряє, чи токен валідний за підписом і ще не протермінований на момент [nowEpochMs].
     */
    fun isTokenActive(token: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val claims = verifyAndDecode(token) ?: return false
        return (claims.exp * 1000L) > nowEpochMs
    }

    /**
     * Повертає кількість секунд до завершення дії токена (<= 0, якщо протермінований або невалідний).
     */
    fun remainingSeconds(token: String, nowEpochMs: Long = System.currentTimeMillis()): Long {
        val claims = decodePayload(token) ?: return 0L
        return ((claims.exp * 1000L) - nowEpochMs) / 1000L
    }

    private fun decodePayloadOnly(encodedPayload: String): JwtClaims? {
        return try {
            val bytes = decodeBase64Url(encodedPayload) ?: return null
            val json = String(bytes, Charsets.UTF_8)
            val obj = JsonParser.parseString(json).asJsonObject
            JwtClaims(
                sub = obj.get("sub")?.asString ?: return null,
                name = obj.get("name")?.asString ?: "",
                role = obj.get("role")?.asString ?: "USER",
                shift = obj.get("shift")?.asString ?: "",
                iat = obj.get("iat")?.asLong ?: 0L,
                exp = obj.get("exp")?.asLong ?: 0L
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun sign(unsignedToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$unsignedToken.$JWT_SECRET".toByteArray(Charsets.UTF_8))
        return encodeBase64Url(digest)
    }

    /** Портативне кодування Base64Url без вирівнювання `=` (сумісне з API 24+ та JVM). */
    internal fun encodeBase64Url(input: ByteArray): String {
        val out = StringBuilder((input.size * 4 + 2) / 3)
        var i = 0
        while (i < input.size) {
            val b0 = input[i].toInt() and 0xFF
            val hasB1 = i + 1 < input.size
            val hasB2 = i + 2 < input.size
            val b1 = if (hasB1) input[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) input[i + 2].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(BASE64_URL_ALPHABET[(triple ushr 18) and 0x3F])
            out.append(BASE64_URL_ALPHABET[(triple ushr 12) and 0x3F])
            if (hasB1) out.append(BASE64_URL_ALPHABET[(triple ushr 6) and 0x3F])
            if (hasB2) out.append(BASE64_URL_ALPHABET[triple and 0x3F])
            i += 3
        }
        return out.toString()
    }

    /** Портативне декодування Base64Url. Повертає null при некоректних символах. */
    internal fun decodeBase64Url(encoded: String): ByteArray? {
        val clean = encoded.trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 4 == 1) return null

        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bitsCollected = 0
        for (ch in clean) {
            val idx = BASE64_URL_ALPHABET.indexOf(ch)
            if (idx < 0) return null
            buffer = (buffer shl 6) or idx
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                out.add(((buffer ushr bitsCollected) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
