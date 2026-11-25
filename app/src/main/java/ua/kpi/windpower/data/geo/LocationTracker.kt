package ua.kpi.windpower.data.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Джерело отриманих геолокаційних координат диспетчера (ПЗ №16).
 */
sealed interface GeoLocationSource {
    data object GpsSensor : GeoLocationSource
    data object NetworkProvider : GeoLocationSource
    data class SimulatedPreset(val cityName: String) : GeoLocationSource
    data object PermissionDeniedFallback : GeoLocationSource

    fun title(): String = when (this) {
        GpsSensor -> "Супутниковий GPS"
        NetworkProvider -> "Мережева локація (Wi-Fi/Cell)"
        is SimulatedPreset -> "Пункт диспетчера: $cityName"
        PermissionDeniedFallback -> "Резерв (без GPS-дозволу)"
    }
}

/**
 * Поточна геопозиція користувача з метаданими точності та джерела.
 */
data class UserGeoPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val source: GeoLocationSource,
    val updatedLabel: String
) {
    fun toCoord(): GeoPointCoord = GeoPointCoord(latitude, longitude)
}

/**
 * Контракт сервісу геолокації для відстеження координат пристрою.
 */
interface LocationTracker {
    fun hasLocationPermission(): Boolean
    fun getLastKnownPosition(): UserGeoPosition?
    fun observeLocationUpdates(minTimeMs: Long = 5000L, minDistanceM: Float = 15f): Flow<UserGeoPosition>
}

/**
 * Реалізація геолокаційного сервісу на базі системного Android [LocationManager]
 * із реактивним потоком [callbackFlow] (інтеграція ПЗ №13 та ПЗ №16).
 *
 * Пояснення роботи з LocationManager:
 * 1. Перед будь-яким зверненням до провайдерів перевіряється наявність runtime-дозволів
 *    `ACCESS_FINE_LOCATION` або `ACCESS_COARSE_LOCATION`.
 * 2. Для миттєвої ініціалізації опитується `getLastKnownLocation` із пріоритетом
 *    `GPS_PROVIDER` → `FUSED_PROVIDER` (API 31+) → `NETWORK_PROVIDER`.
 * 3. Поточні оновлення позиції підписуються через [LocationManager.requestLocationUpdates]
 *    у головному Looper та гарантовано скасовуються в `awaitClose`.
 */
class AndroidLocationTracker(context: Context) : LocationTracker {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    @SuppressLint("MissingPermission")
    override fun getLastKnownPosition(): UserGeoPosition? {
        val manager = locationManager
        if (!hasLocationPermission() || manager == null) return null

        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }

        val candidates = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        val best = candidates.maxByOrNull { it.time } ?: return null
        return best.toUserPosition()
    }

    @SuppressLint("MissingPermission")
    override fun observeLocationUpdates(
        minTimeMs: Long,
        minDistanceM: Float
    ): Flow<UserGeoPosition> = callbackFlow {
        val manager = locationManager
        if (!hasLocationPermission() || manager == null) {
            close()
            return@callbackFlow
        }

        // Спочатку віддаємо останню відому координату пристрою, якщо вона є у системі
        getLastKnownPosition()?.let { trySend(it) }

        val listener = LocationListener { location ->
            trySend(location.toUserPosition())
        }

        val activeProviders = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { isProviderEnabledSafe(manager, it) },
            LocationManager.NETWORK_PROVIDER.takeIf { isProviderEnabledSafe(manager, it) }
        )

        activeProviders.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceM,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }

        awaitClose {
            runCatching { manager.removeUpdates(listener) }
        }
    }

    private fun isProviderEnabledSafe(manager: LocationManager, provider: String): Boolean =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

    private fun Location.toUserPosition(): UserGeoPosition {
        val src = if (provider == LocationManager.GPS_PROVIDER) {
            GeoLocationSource.GpsSensor
        } else {
            GeoLocationSource.NetworkProvider
        }
        return UserGeoPosition(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            source = src,
            updatedLabel = formatNow()
        )
    }

    companion object {
        /** Координати диспетчерського центру НТУУ «КПІ» (Київ) як резерв за замовчуванням. */
        val DEFAULT_DISPATCHER_POSITION = UserGeoPosition(
            latitude = 50.4494,
            longitude = 30.4573,
            accuracyMeters = null,
            source = GeoLocationSource.SimulatedPreset("Київ (НТУУ «КПІ»)"),
            updatedLabel = "початкова позиція"
        )

        fun formatNow(): String =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}
