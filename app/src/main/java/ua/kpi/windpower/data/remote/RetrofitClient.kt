package ua.kpi.windpower.data.remote

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ua.kpi.windpower.data.auth.AuthDataStore
import ua.kpi.windpower.data.auth.AuthInterceptor
import ua.kpi.windpower.data.auth.TokenStorage
import java.util.concurrent.TimeUnit

/**
 * Централізований мережевий клієнт Retrofit2 + OkHttp3 для застосунку AeroGrid 3.0.
 *
 * Поєднує:
 * - [AuthInterceptor] — автоматичне додавання `Authorization: Bearer <token>`;
 * - [MockWindBackendInterceptor] — вбудований REST API для автономної роботи
 *   (може бути вимкнений для переходу на зовнішній сервер `http://10.0.2.2:5156/`);
 * - [GsonConverterFactory] — автоматична серіалізація/десеріалізація JSON.
 */
object RetrofitClient {

    private const val BASE_URL = "http://10.0.2.2:5156/"

    @Volatile
    private var appServices: NetworkServices? = null

    data class NetworkServices(
        val api: WindApiService,
        val tokenStorage: AuthDataStore,
        val mockBackend: MockWindBackendInterceptor
    )

    /**
     * Повертає потокобезпечний singleton мережевого стеку для Android-застосунку.
     */
    fun getInstance(context: Context): NetworkServices =
        appServices ?: synchronized(this) {
            appServices ?: createServices(context.applicationContext).also { appServices = it }
        }

    private fun createServices(appContext: Context): NetworkServices {
        val dataStore = AuthDataStore(appContext)
        val mockInterceptor = MockWindBackendInterceptor(enabled = true)
        val api = createApi(
            tokenStorage = dataStore,
            mockBackend = mockInterceptor
        )
        return NetworkServices(
            api = api,
            tokenStorage = dataStore,
            mockBackend = mockInterceptor
        )
    }

    /**
     * Фабричний метод створення [WindApiService] із можливістю підміни
     * [TokenStorage] та [MockWindBackendInterceptor] у модульних тестах.
     */
    fun createApi(
        tokenStorage: TokenStorage,
        mockBackend: MockWindBackendInterceptor = MockWindBackendInterceptor(enabled = true, simulatedLatencyMs = 0L),
        baseUrl: String = BASE_URL
    ): WindApiService {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenStorage))
            .addInterceptor(mockBackend)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WindApiService::class.java)
    }
}
