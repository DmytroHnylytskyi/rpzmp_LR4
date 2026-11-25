package ua.kpi.windpower.data.network

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запис у журналі мережевих помилок (`error_log.txt`).
 */
data class ErrorLogEntry(
    val timestampMs: Long,
    val formattedTime: String,
    val category: String,
    val httpCode: Int?,
    val message: String
)

/**
 * Інтерфейс журналу реєстрації помилок (файл / пам'ять для тестів).
 */
interface ErrorLogSink {
    fun record(category: String, httpCode: Int?, message: String)
    fun readAll(): List<ErrorLogEntry>
    fun clear()
}

/**
 * Файловий журнал помилок мережевих запитів (ПЗ №12).
 *
 * Зберігає кожну помилку у внутрішній файл `error_log.txt` у директорії
 * `context.filesDir` та дублює запис у системний `Logcat` для діагностики.
 */
class FileErrorLogSink(context: Context) : ErrorLogSink {

    private val logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
    private val lock = Any()

    override fun record(category: String, httpCode: Int?, message: String) {
        val now = System.currentTimeMillis()
        val timeText = formatTime(now)
        val safeMsg = message.replace('\n', ' ').replace('|', '/')
        val line = "$now|$timeText|$category|${httpCode ?: "-"}|$safeMsg\n"

        runCatching { Log.e(LOG_TAG, "[$category code=${httpCode ?: "-"}] $safeMsg") }

        synchronized(lock) {
            try {
                FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(line)
                }
            } catch (e: Exception) {
                runCatching { Log.e(LOG_TAG, "Cannot write error_log.txt: ${e.localizedMessage}") }
            }
        }
    }

    override fun readAll(): List<ErrorLogEntry> = synchronized(lock) {
        if (!logFile.exists()) return emptyList()
        try {
            logFile.readLines(Charsets.UTF_8)
                .asReversed()
                .mapNotNull { parseLine(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun clear() {
        synchronized(lock) {
            if (logFile.exists()) logFile.delete()
        }
    }

    private fun parseLine(line: String): ErrorLogEntry? {
        val parts = line.split("|", limit = 5)
        if (parts.size < 5) return null
        val ts = parts[0].toLongOrNull() ?: return null
        return ErrorLogEntry(
            timestampMs = ts,
            formattedTime = parts[1],
            category = parts[2],
            httpCode = parts[3].toIntOrNull(),
            message = parts[4]
        )
    }

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("HH:mm:ss dd.MM", Locale.getDefault()).format(Date(epochMs))

    companion object {
        private const val LOG_FILE_NAME = "error_log.txt"
        private const val LOG_TAG = "AeroGridErrorHandler"
    }
}

/**
 * In-memory журнал помилок для чистого модульного тестування на JVM.
 */
class InMemoryErrorLogSink : ErrorLogSink {
    private val entries = mutableListOf<ErrorLogEntry>()

    override fun record(category: String, httpCode: Int?, message: String) {
        val now = System.currentTimeMillis()
        entries.add(
            0,
            ErrorLogEntry(
                timestampMs = now,
                formattedTime = now.toString(),
                category = category,
                httpCode = httpCode,
                message = message
            )
        )
    }

    override fun readAll(): List<ErrorLogEntry> = entries.toList()

    override fun clear() {
        entries.clear()
    }
}
