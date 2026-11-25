package ua.kpi.windpower.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.windpower.data.auth.AuthSession
import ua.kpi.windpower.data.auth.JwtUtils
import ua.kpi.windpower.data.remote.dto.ProtectedMessageDto
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.di.AppContainer

/**
 * Стан екрану автентифікації, JWT-сесії та рольового доступу RBAC (ПЗ №11).
 */
data class AuthUiState(
    val session: AuthSession? = null,
    val isTokenExpired: Boolean = false,
    val isLoading: Boolean = false,
    val protectedData: ProtectedMessageDto? = null,
    val adminStations: List<RemoteWindStationDto>? = null,
    val snackbarEvent: UiMessageEvent? = null
)

/**
 * ViewModel керування автентифікацією та авторизацією диспетчерів ВЕС.
 *
 * Поєднує:
 * - вхід/реєстрацію через REST API (`RemoteAuthRepository`) із видачею JWT-токена;
 * - реактивне збереження сесії в `Preferences DataStore`;
 * - автоматичну підстановку токена через `AuthInterceptor`;
 * - перевірку терміну дії JWT та розмежування доступу до захищених і адмін-ресурсів;
 * - синхронізацію облікових записів із таблицею `wind_dispatchers` локальної Room БД.
 */
class DispatcherAuthViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.get(application)
    private val remoteAuth = container.remoteAuthRepository
    private val localDispatcherRepo = container.localDispatcherRepository
    private val authDataStore = container.tokenStorage

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            remoteAuth.sessionFlow.collect { session ->
                val expired = session != null && !JwtUtils.isTokenActive(session.token)
                _uiState.update {
                    it.copy(
                        session = session,
                        isTokenExpired = expired
                    )
                }
            }
        }
    }

    /**
     * Виконує вхід через REST API (`/login`), зберігає JWT у DataStore
     * та передає результат у UI через [onError] при відмові.
     */
    fun loginWithApi(
        username: String,
        password: String,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = remoteAuth.login(username, password)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess { session ->
                _uiState.update {
                    it.copy(
                        protectedData = null,
                        adminStations = null,
                        snackbarEvent = UiMessageEvent(
                            message = "✓ Вхід виконано. Роль у JWT: ${session.role}",
                            isError = false
                        )
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "Помилка авторизації"
                onError(msg)
                _uiState.update {
                    it.copy(snackbarEvent = UiMessageEvent(message = msg, isError = true))
                }
            }
        }
    }

    /**
     * Реєструє нового інженера через REST API (`/register`), дублює запис у Room
     * та зберігає отриманий JWT-токен у DataStore.
     */
    fun registerWithApi(
        fullName: String,
        email: String,
        password: String,
        shift: String,
        role: String,
        smsAlerts: Boolean,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = remoteAuth.register(
                fullName = fullName,
                email = email,
                password = password,
                shift = shift,
                role = role
            )
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess { session ->
                // Синхронізація профілю з таблицею wind_dispatchers локальної БД Room
                runCatching {
                    localDispatcherRepo.register(fullName, email, password, shift, smsAlerts, role = role)
                }
                _uiState.update {
                    it.copy(
                        protectedData = null,
                        adminStations = null,
                        snackbarEvent = UiMessageEvent(
                            message = "✓ Інженера зареєстровано. Роль: ${session.role}",
                            isError = false
                        )
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "Помилка реєстрації"
                onError(msg)
                _uiState.update {
                    it.copy(snackbarEvent = UiMessageEvent(message = msg, isError = true))
                }
            }
        }
    }

    /**
     * Завантажує захищений ресурс `/protected`.
     * При [skipAuthHeader] = true навмисно надсилає запит без токена для перевірки HTTP 401.
     */
    fun loadProtectedData(skipAuthHeader: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val expired = !skipAuthHeader && remoteAuth.isCurrentTokenExpired()
            if (expired) {
                _uiState.update { it.copy(isTokenExpired = true) }
            }

            remoteAuth.loadProtectedData(skipAuthHeader)
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            protectedData = data,
                            snackbarEvent = UiMessageEvent("✓ Захищені дані SCADA отримано", isError = false)
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            snackbarEvent = UiMessageEvent(
                                message = err.message ?: "Помилка доступу до захищених даних диспетчера",
                                isError = true
                            )
                        )
                    }
                }
        }
    }

    /**
     * Завантажує адміністративний реєстр ВЕС (`/admin-only`).
     * Якщо поточний токен має роль `USER`, сервер поверне HTTP 403 Forbidden.
     */
    fun loadAdminStations(skipAuthHeader: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            remoteAuth.loadAdminStations(skipAuthHeader)
                .onSuccess { stations ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            adminStations = stations,
                            snackbarEvent = UiMessageEvent(
                                message = "✓ Адміністративний реєстр ВЕС завантажено (${stations.size})",
                                isError = false
                            )
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            snackbarEvent = UiMessageEvent(
                                message = err.message ?: "Доступ до адміністративного реєстру ВЕС заборонено",
                                isError = true
                            )
                        )
                    }
                }
        }
    }

    /** Створює нову станцію ВЕС (лише ADMIN) та оновлює адмін-список. */
    fun createStationAsAdmin(
        name: String,
        location: String,
        capacityMw: Double,
        turbinesCount: Int
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            remoteAuth.createStationAsAdmin(name, location, capacityMw, turbinesCount)
                .onSuccess { created ->
                    val updatedList = remoteAuth.loadAdminStations().getOrNull()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            adminStations = updatedList ?: it.adminStations,
                            snackbarEvent = UiMessageEvent(
                                message = "✓ ВЕС «${created.name}» збережено в реєстр та БД",
                                isError = false
                            )
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            snackbarEvent = UiMessageEvent(
                                message = err.message ?: "Помилка створення ВЕС",
                                isError = true
                            )
                        )
                    }
                }
        }
    }

    /**
     * Демонстраційно встановлює термін дії токена в минулому, щоб показати
     * механізм виявлення протермінованого токена та запит повторного входу.
     */
    fun simulateExpiredToken() {
        viewModelScope.launch {
            authDataStore.expireCurrentTokenForDemo()
            _uiState.update {
                it.copy(
                    isTokenExpired = true,
                    snackbarEvent = UiMessageEvent(
                        message = "Токен позначено як протермінований! Спробуйте завантажити дані.",
                        isError = true
                    )
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            remoteAuth.logout()
            _uiState.update {
                AuthUiState(
                    snackbarEvent = UiMessageEvent("Сесію завершено, токен очищено з DataStore", isError = false)
                )
            }
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarEvent = null) }
    }
}
