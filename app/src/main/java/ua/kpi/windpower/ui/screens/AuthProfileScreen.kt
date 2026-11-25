package ua.kpi.windpower.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windpower.data.Validators
import ua.kpi.windpower.data.auth.AuthSession
import ua.kpi.windpower.data.auth.JwtUtils
import ua.kpi.windpower.ui.components.AppTextField
import ua.kpi.windpower.ui.components.LoadingIndicator
import ua.kpi.windpower.ui.components.ScreenTitle
import ua.kpi.windpower.ui.components.StatusChip
import ua.kpi.windpower.ui.theme.AmberWarning
import ua.kpi.windpower.ui.theme.CyanAccent
import ua.kpi.windpower.ui.theme.EmeraldLight
import ua.kpi.windpower.ui.theme.StormAlert
import ua.kpi.windpower.ui.viewmodels.AuthUiState
import ua.kpi.windpower.ui.viewmodels.DispatcherAuthViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Екран диспетчера: JWT-автентифікація через REST API, збереження токена у DataStore,
 * автоматична підстановка заголовка Authorization через OkHttp AuthInterceptor
 * та розмежування прав доступу у UI (RBAC) для ролей `ADMIN` і `USER` (ПЗ №11).
 */
@Composable
fun AuthProfileScreen(
    viewModel: DispatcherAuthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarEvent?.id) {
        uiState.snackbarEvent?.let { event ->
            snackbarHostState.showSnackbar(event.message)
            viewModel.consumeSnackbar()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        val session = uiState.session
        if (session != null) {
            DispatcherProfileView(
                session = session,
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            DispatcherAuthView(
                isLoading = uiState.isLoading,
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

/**
 * Профіль активної JWT-сесії чергового диспетчера з розмежуванням UI за роллю (`ADMIN` / `USER`).
 */
@Composable
private fun DispatcherProfileView(
    session: AuthSession,
    uiState: AuthUiState,
    viewModel: DispatcherAuthViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isAdmin = session.role.equals("ADMIN", ignoreCase = true)
    val claims = remember(session.token) { JwtUtils.decodePayload(session.token) }
    var showCreateStationDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle("Кабінет диспетчера • JWT")

        // 1. Картка профілю та ролі
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background((if (isAdmin) EmeraldLight else CyanAccent).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = if (isAdmin) "👑" else "⚡", fontSize = 30.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = session.fullName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = session.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))
                StatusChip(
                    text = if (isAdmin) "РОЛЬ: ADMIN (ГОЛОВНИЙ ДИСПЕТЧЕР)" else "РОЛЬ: USER (ОПЕРАТОР ЗМІНИ)",
                    color = if (isAdmin) EmeraldLight else CyanAccent
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    ProfileBadge(
                        label = "Токен JWT",
                        value = if (uiState.isTokenExpired) "Протермінований" else "Дійсний",
                        color = if (uiState.isTokenExpired) StormAlert else EmeraldLight
                    )
                    ProfileBadge(
                        label = "Рівень RBAC",
                        value = if (isAdmin) "Повний (Admin)" else "Стандарт (User)",
                        color = if (isAdmin) EmeraldLight else CyanAccent
                    )
                    ProfileBadge(
                        label = "Чергування",
                        value = session.shift,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 2. Попередження при протермінованому токені
        if (uiState.isTokenExpired) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                border = BorderStroke(1.dp, StormAlert),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "⚠ ТЕРМІН ДІЇ ТОКЕНА ЗАВЕРШИВСЯ",
                        style = MaterialTheme.typography.labelLarge,
                        color = StormAlert
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ваш JWT-токен протермінований. Виконайте повторний вхід, щоб відновити доступ до захищених ендпоінтів.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.logout() },
                        colors = ButtonDefaults.buttonColors(containerColor = StormAlert)
                    ) {
                        Text("Увійти повторно")
                    }
                }
            }
        }

        // 3. Інспектор JWT-токена та DataStore
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ПАРАМЕТРИ СЕСІЇ ТА ТОКЕНА ДОСТУПУ",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanAccent
                )
                Text(
                    text = "Bearer ${session.token.take(44)}...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                claims?.let {
                    ProfileInfoRow(label = "Subject (sub):", value = it.sub)
                    ProfileInfoRow(label = "Роль у Payload:", value = it.role)
                    ProfileInfoRow(label = "Дійсний до:", value = formatEpochSec(it.exp))
                }
                OutlinedButton(
                    onClick = { viewModel.simulateExpiredToken() },
                    enabled = !uiState.isLoading && !uiState.isTokenExpired,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarning),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⏱ Примусово завершити термін сесії")
                }
            }
        }

        // 4. Захищені ресурси REST API (Bearer Authorization)
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "КОНТРОЛЬ ДОСТУПУ ТА АВТОРИЗАЦІЇ",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldLight
                )

                Button(
                    onClick = { viewModel.loadProtectedData(skipAuthHeader = false) },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔓 Отримати диспетчерське зведення")
                }

                uiState.protectedData?.let { data ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = data.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Підтверджена роль сервера: ${data.issuedForRole}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.loadProtectedData(skipAuthHeader = true) },
                    enabled = !uiState.isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StormAlert),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🚫 Запит без авторизації")
                }
            }
        }

        // 5. Адаптація UI під роль користувача (RBAC)
        if (isAdmin) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "АДМІНІСТРАТИВНА ПАНЕЛЬ SCADA (ADMIN)",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldLight
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.loadAdminStations() },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Реєстр адміністратора")
                        }
                        OutlinedButton(
                            onClick = { showCreateStationDialog = true },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+ Додати ВЕС")
                        }
                    }

                    uiState.adminStations?.forEach { station ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "⚡ ${station.name}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "${station.location} • Потужність: ${station.capacityMw} МВт • ${station.turbinesCount} турбін",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ПРАВА ДОСТУПУ ОПЕРАТОРА (USER)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberWarning
                    )
                    Text(
                        text = "Функції адміністрування реєстру станцій доступні лише для ролі головного диспетчера (ADMIN).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { viewModel.loadAdminStations() },
                        enabled = !uiState.isLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarning),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔒 Запит адміністративного реєстру")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { viewModel.logout() },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StormAlert)
        ) {
            Text("Завершити зміну та вийти")
        }
    }

    if (showCreateStationDialog) {
        AdminCreateStationDialog(
            onDismiss = { showCreateStationDialog = false },
            onConfirm = { name, location, capacity, turbines ->
                viewModel.createStationAsAdmin(name, location, capacity, turbines)
                showCreateStationDialog = false
            }
        )
    }
}

/**
 * Форма автентифікації та реєстрації оператора через REST API з видачею JWT (ПЗ №11).
 */
@Composable
private fun DispatcherAuthView(
    isLoading: Boolean,
    viewModel: DispatcherAuthViewModel,
    modifier: Modifier = Modifier
) {
    var isRegisterMode by remember { mutableStateOf(false) }

    var loginInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }

    val shiftOptions = listOf("Зміна №1 (Денна)", "Зміна №2 (Нічна)", "Зміна №3 (Резервна)")
    var selectedShift by remember { mutableStateOf(shiftOptions[0]) }
    val roleOptions = listOf("USER" to "Оператор зміни (USER)", "ADMIN" to "Головний диспетчер (ADMIN)")
    var selectedRole by remember { mutableStateOf("USER") }
    var isEmergencySmsEnabled by remember { mutableStateOf(true) }
    var isSafetyConfirmed by remember { mutableStateOf(false) }

    var nameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmPasswordError by remember { mutableStateOf<String?>(null) }
    var safetyError by remember { mutableStateOf<String?>(null) }
    var generalError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    fun validateForm(): Boolean {
        var isValid = true
        nameError = null
        emailError = null
        passwordError = null
        confirmPasswordError = null
        safetyError = null
        generalError = null

        if (isRegisterMode) {
            if (nameInput.trim().length < 3) {
                nameError = "Вкажіть коректне ПІБ (щонайменше 3 символи)"
                isValid = false
            }
            if (!Validators.isValidEmail(emailInput)) {
                emailError = "Введіть коректну адресу корпоративного email"
                isValid = false
            }
            if (!Validators.isStrongPassword(passwordInput)) {
                passwordError = "Пароль має містити від 8 символів, велику літеру та цифру"
                isValid = false
            }
            if (passwordInput != confirmPasswordInput) {
                confirmPasswordError = "Паролі не збігаються"
                isValid = false
            }
            if (!isSafetyConfirmed) {
                safetyError = "Необхідно підтвердити ознайомлення з регламентом безпеки"
                isValid = false
            }
        } else {
            if (loginInput.isBlank()) {
                generalError = "Введіть корпоративний email диспетчера"
                isValid = false
            }
            if (passwordInput.isBlank()) {
                passwordError = "Введіть пароль"
                isValid = false
            }
        }
        return isValid
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScreenTitle(if (isRegisterMode) "Реєстрація інженера ВЕС" else "Автентифікація (JWT API)")

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isRegisterMode) "Створення облікового запису + видача JWT" else "Вхід через POST /login",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EmeraldLight
                )

                if (!isRegisterMode) {
                    Text(
                        text = "Швидкий вхід оперативного персоналу:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                loginInput = "admin@windpower.kpi.ua"
                                passwordInput = "Admin2026"
                                generalError = null
                                passwordError = null
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("👑 ADMIN", style = MaterialTheme.typography.labelMedium) }

                        OutlinedButton(
                            onClick = {
                                loginInput = "operator@windpower.kpi.ua"
                                passwordInput = "User2026"
                                generalError = null
                                passwordError = null
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("⚡ USER", style = MaterialTheme.typography.labelMedium) }
                    }
                }

                if (isRegisterMode) {
                    AppTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            if (nameError != null) nameError = null
                        },
                        label = "ПІБ інженера",
                        errorMessage = nameError
                    )
                    AppTextField(
                        value = emailInput,
                        onValueChange = {
                            emailInput = it
                            if (emailError != null) emailError = null
                        },
                        label = "Корпоративний Email",
                        helperText = "Формат: engineer@windpower.kpi.ua",
                        errorMessage = emailError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )

                    Text(
                        text = "Роль доступу в JWT-токені:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    roleOptions.forEach { (roleCode, roleLabel) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (selectedRole == roleCode),
                                    onClick = { selectedRole = roleCode }
                                )
                        ) {
                            RadioButton(
                                selected = (selectedRole == roleCode),
                                onClick = { selectedRole = roleCode },
                                colors = RadioButtonDefaults.colors(selectedColor = CyanAccent)
                            )
                            Text(text = roleLabel, fontSize = 13.sp)
                        }
                    }

                    Text(
                        text = "Чергова зміна диспетчера:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    shiftOptions.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (selectedShift == option),
                                    onClick = { selectedShift = option }
                                )
                        ) {
                            RadioButton(
                                selected = (selectedShift == option),
                                onClick = { selectedShift = option },
                                colors = RadioButtonDefaults.colors(selectedColor = EmeraldLight)
                            )
                            Text(text = option, fontSize = 13.sp)
                        }
                    }

                    AppTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            if (passwordError != null) passwordError = null
                        },
                        label = "Пароль (від 8 символів)",
                        errorMessage = passwordError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    AppTextField(
                        value = confirmPasswordInput,
                        onValueChange = {
                            confirmPasswordInput = it
                            if (confirmPasswordError != null) confirmPasswordError = null
                        },
                        label = "Підтвердження пароля",
                        errorMessage = confirmPasswordError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Аварійні SMS-сповіщення", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Сповіщення при штормовому вітрі > 25 м/с",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isEmergencySmsEnabled,
                            onCheckedChange = { isEmergencySmsEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = EmeraldLight)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                isSafetyConfirmed = !isSafetyConfirmed
                                if (safetyError != null) safetyError = null
                            }
                    ) {
                        Checkbox(
                            checked = isSafetyConfirmed,
                            onCheckedChange = {
                                isSafetyConfirmed = it
                                if (safetyError != null) safetyError = null
                            },
                            colors = CheckboxDefaults.colors(checkedColor = EmeraldLight)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Підтверджую проходження інструктажу з безпеки SCADA ВЕС",
                            fontSize = 12.sp,
                            color = if (safetyError != null) StormAlert else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (safetyError != null) {
                        Text(text = safetyError!!, color = StormAlert, fontSize = 11.sp)
                    }
                } else {
                    AppTextField(
                        value = loginInput,
                        onValueChange = {
                            loginInput = it
                            if (generalError != null) generalError = null
                        },
                        label = "Корпоративний Email диспетчера",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    AppTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            if (passwordError != null) passwordError = null
                        },
                        label = "Пароль доступу",
                        errorMessage = passwordError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation()
                    )
                }

                if (generalError != null) {
                    Text(text = generalError!!, color = StormAlert, fontSize = 12.sp)
                }

                if (isLoading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LoadingIndicator()
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isRegisterMode) "Реєстрація та випуск JWT..." else "Авторизація через API...",
                            fontSize = 12.sp,
                            color = EmeraldLight
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            if (validateForm()) {
                                if (isRegisterMode) {
                                    viewModel.registerWithApi(
                                        fullName = nameInput.trim(),
                                        email = emailInput.trim(),
                                        password = passwordInput,
                                        shift = selectedShift,
                                        role = selectedRole,
                                        smsAlerts = isEmergencySmsEnabled
                                    ) { err -> generalError = err }
                                } else {
                                    viewModel.loginWithApi(
                                        username = loginInput.trim(),
                                        password = passwordInput
                                    ) { err -> generalError = err }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            text = if (isRegisterMode) "Зареєструвати та увійти" else "Увійти на зміну (отримати JWT)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                TextButton(
                    onClick = {
                        isRegisterMode = !isRegisterMode
                        nameError = null
                        emailError = null
                        passwordError = null
                        confirmPasswordError = null
                        safetyError = null
                        generalError = null
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = if (isRegisterMode) "Вже маєте обліковий запис? Увійти" else "Новий інженер? Зареєструватися",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminCreateStationDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, location: String, capacityMw: Double, turbinesCount: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("Одеська обл.") }
    var capacity by remember { mutableStateOf("150.0") }
    var turbines by remember { mutableStateOf("30") }
    var attempted by remember { mutableStateOf(false) }

    val capVal = capacity.toDoubleOrNull()
    val turbVal = turbines.toIntOrNull()
    val nameErr = if (attempted && name.isBlank()) "Введіть назву ВЕС" else null
    val capErr = if (attempted && (capVal == null || capVal <= 0.0)) "Введіть додатне значення потужності" else null
    val turbErr = if (attempted && (turbVal == null || turbVal < 1)) "Вкажіть щонайменше 1 турбіну" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Нова вітроелектростанція", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppTextField(value = name, onValueChange = { name = it }, label = "Назва ВЕС", errorMessage = nameErr)
                AppTextField(value = location, onValueChange = { location = it }, label = "Локація")
                AppTextField(
                    value = capacity,
                    onValueChange = { capacity = it },
                    label = "Потужність (МВт)",
                    errorMessage = capErr,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                AppTextField(
                    value = turbines,
                    onValueChange = { turbines = it },
                    label = "Кількість турбін",
                    errorMessage = turbErr,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                attempted = true
                if (name.isNotBlank() && capVal != null && capVal > 0.0 && turbVal != null && turbVal >= 1) {
                    onConfirm(name.trim(), location.trim().ifBlank { "Україна" }, capVal, turbVal)
                }
            }) { Text("Створити") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )
}

@Composable
private fun ProfileBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun ProfileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatEpochSec(epochSeconds: Long): String =
    SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000L))
