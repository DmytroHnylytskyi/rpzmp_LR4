package ua.kpi.windpower.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windpower.data.Validators
import ua.kpi.windpower.data.db.WindStationSeedCatalog
import ua.kpi.windpower.ui.components.AppTextField
import ua.kpi.windpower.ui.components.LoadingIndicator
import ua.kpi.windpower.ui.components.ScreenTitle
import ua.kpi.windpower.ui.theme.StormAlert
import ua.kpi.windpower.ui.viewmodels.AddStationResult
import ua.kpi.windpower.ui.viewmodels.WindDatabaseViewModel
import java.util.Locale

/**
 * Екран реєстрації нового об'єкта ВЕС із клієнтською валідацією та записом у Room БД.
 *
 * Перевіряє коректність назви, області, кількості ВЕУ, email відповідального інженера,
 * стійкості пароля та номінальної потужності, після чого зберігає ВЕС зі стартовою
 * телеметрією в єдину локальну БД Room.
 */
@Composable
fun StationRegistrationScreen(
    onNavigateBack: (() -> Unit)? = null,
    databaseViewModel: WindDatabaseViewModel = viewModel()
) {
    var stationName by remember { mutableStateOf("") }
    var stationLocation by remember { mutableStateOf("Одеська обл.") }
    var turbinesInput by remember { mutableStateOf("12") }
    var operatorEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var capacityInput by remember { mutableStateOf("") }

    var attempted by remember { mutableStateOf(false) }
    var isSubmitted by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val capacityValue = capacityInput.trim().toDoubleOrNull()
    val turbinesValue = turbinesInput.trim().toIntOrNull()

    val nameError = if (attempted && stationName.trim().length < 3) {
        "Назва станції має містити щонайменше 3 символи"
    } else null
    val turbinesError = if (attempted && (turbinesValue == null || turbinesValue < 1)) {
        "Вкажіть кількість ВЕУ (від 1)"
    } else null
    val emailError = when {
        !attempted || operatorEmail.isBlank() -> null
        !Validators.isValidEmail(operatorEmail) -> "Некоректний формат email (ім'я@домен)"
        else -> null
    }
    val passwordError = when {
        !attempted || password.isBlank() -> null
        !Validators.isStrongPassword(password) ->
            "Пароль має містити ≥${Validators.MIN_PASSWORD_LENGTH} символів, велику літеру та цифру"
        else -> null
    }
    val capacityError = when {
        !attempted -> null
        !Validators.isValidCapacityMw(capacityValue) -> "Введіть додатне число (наприклад, 34.0)"
        else -> null
    }
    val hasErrors = nameError != null || turbinesError != null || emailError != null ||
        passwordError != null || capacityError != null ||
        operatorEmail.isBlank() || password.isBlank() || capacityInput.isBlank()

    val passwordStrength = cipherStrength(password)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top
    ) {
        if (onNavigateBack != null) {
            Button(
                onClick = onNavigateBack,
                colors = ButtonDefaults.filledTonalButtonColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text("← Повернутися до бази даних")
            }
        }

        ScreenTitle("Реєстрація нового об'єкта ВЕС")

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppTextField(
                    value = stationName,
                    onValueChange = {
                        stationName = it
                        submitError = null
                        isSubmitted = false
                    },
                    label = "Назва вітропарку (ВЕС)",
                    helperText = "Офіційна назва (напр. «Овідіопольська ВЕС»)",
                    errorMessage = nameError
                )

                AppTextField(
                    value = stationLocation,
                    onValueChange = { stationLocation = it },
                    label = "Локація (область України)",
                    helperText = "Координати об'єкта прив'яжуться автоматично за областю"
                )

                AppTextField(
                    value = turbinesInput,
                    onValueChange = { turbinesInput = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = "Кількість вітроустановок (ВЕУ)",
                    errorMessage = turbinesError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                AppTextField(
                    value = operatorEmail,
                    onValueChange = { operatorEmail = it },
                    label = "Email чергового інженера",
                    helperText = "Формат: engineer@windpower.kpi.ua",
                    errorMessage = if (attempted && operatorEmail.isBlank()) "Введіть корпоративний email" else emailError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                AppTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Пароль доступу до SCADA",
                    helperText = if (password.isBlank()) {
                        "Мін. ${Validators.MIN_PASSWORD_LENGTH} символів, велика літера та цифра"
                    } else null,
                    errorMessage = if (attempted && password.isBlank()) "Введіть пароль доступу" else passwordError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation()
                )
                if (password.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { passwordStrength },
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp),
                            color = when {
                                passwordStrength >= 1f -> MaterialTheme.colorScheme.primary
                                passwordStrength >= 0.66f -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Text(
                            text = when {
                                passwordStrength >= 1f -> "Надійний"
                                passwordStrength >= 0.66f -> "Середній"
                                else -> "Слабкий"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AppTextField(
                    value = capacityInput,
                    onValueChange = { capacityInput = it },
                    label = "Встановлена потужність (МВт)",
                    helperText = "Сумарна номінальна потужність генераторів (напр. 34.0)",
                    errorMessage = capacityError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        }

        submitError?.let { msg ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = msg, color = StormAlert, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                attempted = true
                submitError = null
                if (!hasErrors && capacityValue != null && turbinesValue != null) {
                    isSaving = true
                    val (powers, speeds, voltages) = WindStationSeedCatalog.defaultTelemetryFor(capacityValue)
                    databaseViewModel.addStationWithTelemetry(
                        name = stationName.trim(),
                        location = stationLocation.trim().ifBlank { "Україна" },
                        capacity = capacityValue,
                        turbinesCount = turbinesValue,
                        description = "Зареєстровано відповідальним інженером (${operatorEmail.trim()}).",
                        powers = powers,
                        speeds = speeds,
                        voltages = voltages
                    ) { result ->
                        isSaving = false
                        when (result) {
                            is AddStationResult.Success -> isSubmitted = true
                            is AddStationResult.Error -> {
                                isSubmitted = false
                                submitError = result.message
                            }
                        }
                    }
                }
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(if (isSaving) "Збереження в Room БД..." else "Зареєструвати ВЕС у БД")
        }

        if (isSaving) {
            Spacer(modifier = Modifier.height(16.dp))
            LoadingIndicator()
        }

        if (isSubmitted) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "✓ ВЕС успішно збережена в базу даних!",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Станція «${stationName.trim()}» (${
                            String.format(Locale.US, "%.1f", capacityValue ?: 0.0)
                        } МВт, ${turbinesValue ?: 10} ВЕУ) вже доступна в реєстрі, прогнозах і карті.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

private fun cipherStrength(password: String): Float {
    if (password.isEmpty()) return 0f
    var criteria = 0
    if (password.length >= Validators.MIN_PASSWORD_LENGTH) criteria++
    if (password.any { it.isUpperCase() }) criteria++
    if (password.any { it.isDigit() }) criteria++
    val lengthProgress = (password.length.toFloat() / Validators.MIN_PASSWORD_LENGTH).coerceAtMost(1f)
    return ((criteria / 3f) * 0.75f + lengthProgress * 0.25f).coerceIn(0f, 1f)
}
