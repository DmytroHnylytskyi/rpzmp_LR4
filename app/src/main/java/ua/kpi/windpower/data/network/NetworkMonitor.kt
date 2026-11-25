package ua.kpi.windpower.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Абстракція перевірки наявності мережевого з'єднання перед виконанням HTTP-запитів (ПЗ №12).
 */
interface NetworkStatusProvider {
    fun isInternetAvailable(): Boolean
}

/**
 * Виняток відсутності активного підключення до Інтернету або увімкненого автономного режиму.
 */
class OfflineNetworkException(
    message: String = "Немає підключення до Інтернету"
) : java.io.IOException(message)

/**
 * Контролер стану мережі Android на базі [ConnectivityManager] та [NetworkCapabilities].
 *
 * Також містить прапорець [simulatedOffline], який дозволяє диспетчеру або студенту
 * протестувати реакцію застосунку на втрату мережі та fallback на локальний кеш
 * без вимкнення Wi-Fi на всьому комп'ютері чи емуляторі.
 */
class AndroidNetworkMonitor(context: Context) : NetworkStatusProvider {

    private val appContext = context.applicationContext

    @Volatile
    var simulatedOffline: Boolean = false

    override fun isInternetAvailable(): Boolean {
        if (simulatedOffline) return false
        return checkSystemConnectivity(appContext)
    }

    companion object {
        fun checkSystemConnectivity(context: Context): Boolean {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
