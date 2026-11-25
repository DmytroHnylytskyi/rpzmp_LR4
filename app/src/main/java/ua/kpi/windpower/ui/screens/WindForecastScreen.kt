package ua.kpi.windpower.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ua.kpi.windpower.data.datasource.ForecastSortMode
import ua.kpi.windpower.data.remote.dto.RemoteWindStationDto
import ua.kpi.windpower.data.remote.dto.WindForecastDto
import ua.kpi.windpower.data.repository.DataOrigin
import ua.kpi.windpower.navigation.Screen
import kotlin.math.abs
import ua.kpi.windpower.ui.components.AppTextField
import ua.kpi.windpower.ui.components.ConfirmDialog
import ua.kpi.windpower.ui.components.EmptyState
import ua.kpi.windpower.ui.components.InfoRow
import ua.kpi.windpower.ui.components.MenuButton
import ua.kpi.windpower.ui.components.ScreenTitle
import ua.kpi.windpower.ui.components.SectionHeader
import ua.kpi.windpower.ui.components.StatCard
import ua.kpi.windpower.ui.components.StatusChip
import ua.kpi.windpower.ui.components.TelemetryMetricCard
import ua.kpi.windpower.ui.theme.AmberWarning
import ua.kpi.windpower.ui.theme.CyanAccent
import ua.kpi.windpower.ui.theme.EmeraldLight
import ua.kpi.windpower.ui.theme.StormAlert
import ua.kpi.windpower.ui.viewmodels.WindForecastViewModel
import java.util.Locale

/**
 * Екран прогнозування потужності ВЕС з повною інтеграцією REST API (ПЗ №9–12):
 * - Repository + LocalDataSource (Room кеш) + RemoteDataSource (Retrofit2 + Gson);
 * - TTL-кешування, офлайн fallback, CRUD прогнозів, фільтрація та сортування;
 * - мультиендпоінтна статистика ВЕС та автогенерація прогнозів;
 * - централізована обробка мережевих помилок, retry-механізм і журнал `error_log.txt`;
 * - передача параметрів навігації (`userName` -> `turbinesCount`) з ЛР №2.
 */
@Composable
fun WindForecastScreen(
    navController: NavHostController,
    userName: String,
    viewModel: WindForecastViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var turbinesCountInput by rememberSaveable { mutableStateOf("6") }
    var turbinesError by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingForecast by remember { mutableStateOf<WindForecastDto?>(null) }
    var deletingForecast by remember { mutableStateOf<WindForecastDto?>(null) }
    var showErrorLogPanel by remember { mutableStateOf(false) }
    var showDiagnosticsTools by remember { mutableStateOf(false) }

    LaunchedEffect(state.snackbarEvent?.id) {
        state.snackbarEvent?.let { event ->
            snackbarHostState.showSnackbar(event.message)
            viewModel.consumeSnackbar()
        }
    }

    val originColor = when (state.dataOrigin) {
        DataOrigin.REMOTE_SYNCED -> EmeraldLight
        DataOrigin.CACHE_FRESH -> CyanAccent
        DataOrigin.OFFLINE_CACHE_FALLBACK -> AmberWarning
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isLargeScreen = maxWidth > 600.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ScreenTitle("Прогноз ВЕС • $userName")

                // --- 1. Стан мережі, джерело даних (Repository) та перемикачі ---
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusChip(text = state.dataOrigin.title.uppercase(), color = originColor)
                            Text(
                                text = "Синхр.: ${state.lastSyncTime}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.refreshFromServer() },
                                enabled = !state.isLoading,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("↻ Оновити з API")
                            }
                            OutlinedButton(
                                onClick = { viewModel.generateForecastsForSelectedStation(3) },
                                enabled = !state.isLoading,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("⚡ Автопрогноз +3 дні")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Автономний режим (Offline)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Робота з локальним кешем без мережі",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.isOfflineSimulated,
                                onCheckedChange = { viewModel.toggleSimulatedOffline(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = AmberWarning)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Джерело синхронізації API",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (state.useRealServer) "Зовнішній хост (10.0.2.2:5156)" else "Локальний шлюз SCADA",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.useRealServer,
                                onCheckedChange = { viewModel.toggleRealServerMode(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = CyanAccent)
                            )
                        }

                        if (state.isLoading || state.retryBanner != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp).width(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = state.retryBanner ?: "Виконується запит до REST API...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.retryBanner != null) AmberWarning else CyanAccent
                                )
                            }
                        }
                    }
                }

                // --- 2. Агрегована бізнес-аналітика Repository (ПЗ №9) ---
                StatCard(
                    title = "Середня прогнозна генерація",
                    value = String.format(Locale.US, "%.2f", state.summary.averagePowerMw),
                    unit = "МВт",
                    accent = EmeraldLight,
                    badge = {
                        StatusChip(
                            text = "${state.summary.count} ПРОГНОЗІВ",
                            color = CyanAccent
                        )
                    },
                    caption = "Пікова потужність: ${String.format(Locale.US, "%.1f", state.summary.peakPowerMw)} МВт • " +
                        "Активна генерація: ${String.format(Locale.US, "%.0f", state.summary.activeGenerationSharePercent)}%"
                )

                // --- 3. Мультиендпоінтний вибір ВЕС та статистика станції (ПЗ №10) ---
                SectionHeader(
                    title = "Станції ВЕС (REST API)",
                    trailing = "${state.stations.size} об'єктів"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StationFilterChip(
                        label = "Усі станції",
                        selected = state.selectedStationId == null,
                        onClick = { viewModel.selectStation(null) }
                    )
                    state.stations.forEach { station ->
                        StationFilterChip(
                            label = "${station.name} (${station.capacityMw.toInt()} МВт)",
                            selected = state.selectedStationId == station.id,
                            onClick = { viewModel.selectStation(station.id) }
                        )
                    }
                }

                state.stationStats?.let { stats ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "ЗВЕДЕНА СТАТИСТИКА ВЕС #${stats.stationId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                TelemetryMetricCard(
                                    label = "Сер. вітер",
                                    value = "${fmt2(stats.averageWindSpeedMps)} м/с",
                                    accentColor = CyanAccent
                                )
                                TelemetryMetricCard(
                                    label = "Сер. потужн.",
                                    value = "${fmt2(stats.averagePowerMw)} МВт",
                                    accentColor = EmeraldLight
                                )
                                TelemetryMetricCard(
                                    label = "Ефективність",
                                    value = "${fmt2(stats.efficiencyPercent)}%",
                                    accentColor = AmberWarning
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            InfoRow("Середня температура повітря:", "${fmt2(stats.averageTemperatureC)} °C")
                            InfoRow("Середня турбулентність:", "${fmt2(stats.averageTurbulencePercent)} %")
                        }
                    }
                }

                // --- 4. Пошук, сортування та CRUD прогнозів (ПЗ №9) ---
                SectionHeader(
                    title = "Графік прогнозів",
                    trailing = "${state.forecasts.size} записів"
                )
                AppTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    label = "Фільтр за датою або нотаткою (напр. 2026-11)"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ForecastSortMode.entries.forEach { mode ->
                        StationFilterChip(
                            label = mode.label,
                            selected = state.sortMode == mode,
                            onClick = { viewModel.onSortModeChange(mode) }
                        )
                    }
                }

                Button(
                    onClick = { showAddDialog = true },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Додати новий прогноз у REST API та кеш")
                }

                if (state.forecasts.isEmpty()) {
                    EmptyState(
                        title = "Прогнозів не знайдено",
                        subtitle = "Змініть фільтр або натисніть «Оновити з API» / «Додати новий прогноз».",
                        iconText = "🌬️"
                    )
                } else {
                    state.forecasts.forEach { forecast ->
                        val stationName = state.stations.firstOrNull { it.id == forecast.stationId }?.name
                            ?: "ВЕС #${forecast.stationId}"
                        ForecastItemCard(
                            forecast = forecast,
                            stationName = stationName,
                            onEdit = { editingForecast = forecast },
                            onDelete = { deletingForecast = forecast }
                        )
                    }
                }

                // --- 5. Діагностика мережі та канал зв'язку (ПЗ №12) ---
                SectionHeader(
                    title = "Діагностика мережі та канал зв'язку",
                    trailing = "${state.errorLogs.size} подій у лозі"
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.todayForecast?.let { today ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Оперативний зріз на ${today.date}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Вітер ${today.windSpeedMps} м/с • ${today.predictedPowerMw} МВт • ${today.notes ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        if (isLargeScreen) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.fetchTodayForecast(flaky = false) },
                                    enabled = !state.isLoading,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Оперативний метеозріз") }
                                Button(
                                    onClick = { viewModel.fetchTodayForecast(flaky = true) },
                                    enabled = !state.isLoading,
                                    colors = ButtonDefaults.filledTonalButtonColors(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Нестабільний канал (Retry ×3)") }
                            }
                        } else {
                            Button(
                                onClick = { viewModel.fetchTodayForecast(flaky = false) },
                                enabled = !state.isLoading,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Оперативний метеозріз на сьогодні") }
                            Button(
                                onClick = { viewModel.fetchTodayForecast(flaky = true) },
                                enabled = !state.isLoading,
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Запит за нестабільного каналу (Retry ×3)") }
                        }

                        TextButton(
                            onClick = { showDiagnosticsTools = !showDiagnosticsTools },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (showDiagnosticsTools) "▲ Сховати перевірку збоїв протоколу" else "▼ Перевірка обробки збоїв каналу",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (showDiagnosticsTools) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.simulateHttpError(400) },
                                    enabled = !state.isLoading,
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Запит 400", style = MaterialTheme.typography.labelMedium) }
                                OutlinedButton(
                                    onClick = { viewModel.simulateHttpError(404) },
                                    enabled = !state.isLoading,
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Ресурс 404", style = MaterialTheme.typography.labelMedium) }
                                OutlinedButton(
                                    onClick = { viewModel.simulateHttpError(500) },
                                    enabled = !state.isLoading,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StormAlert),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Сервер 500 (×3)", style = MaterialTheme.typography.labelMedium) }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                viewModel.refreshErrorLogs()
                                showErrorLogPanel = !showErrorLogPanel
                            }) {
                                Text(
                                    text = if (showErrorLogPanel) "▲ Сховати error_log.txt" else "▼ Показати error_log.txt (${state.errorLogs.size})",
                                    color = CyanAccent
                                )
                            }
                            if (state.errorLogs.isNotEmpty()) {
                                TextButton(onClick = { viewModel.clearErrorLogs() }) {
                                    Text("Очистити", color = StormAlert)
                                }
                            }
                        }

                        if (showErrorLogPanel) {
                            if (state.errorLogs.isEmpty()) {
                                Text(
                                    text = "Журнал error_log.txt поки порожній.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                state.errorLogs.take(8).forEach { entry ->
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = "${entry.formattedTime} • ${entry.category}" +
                                                    (entry.httpCode?.let { " [HTTP $it]" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = AmberWarning
                                            )
                                            Text(
                                                text = entry.message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- 6. Навігація до інженерної деталізації (ЛР №2 спадковість) ---
                SectionHeader(title = "Інженерний розрахунок парку")
                OutlinedTextField(
                    value = turbinesCountInput,
                    onValueChange = { raw ->
                        turbinesCountInput = raw.filter { it.isDigit() }.take(5)
                        turbinesError = null
                    },
                    label = { Text("Кількість діючих турбін ВЕУ в строю") },
                    isError = turbinesError != null,
                    supportingText = turbinesError?.let { msg ->
                        { Text(text = msg, color = StormAlert) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isLargeScreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MenuButton(
                            text = "Розрахувати інженерні деталі",
                            modifier = Modifier.weight(1f)
                        ) {
                            val count = turbinesCountInput.trim().toIntOrNull()
                            if (count == null || count < 1) {
                                turbinesError = "Вкажіть цілу кількість турбін (від 1)"
                            } else {
                                navController.navigate(Screen.WindDetails.createRoute(count))
                            }
                        }
                        MenuButton(
                            text = "До моніторингу",
                            modifier = Modifier.weight(1f)
                        ) {
                            navController.navigate(Screen.Dashboard.route)
                        }
                    }
                } else {
                    MenuButton("Розрахувати інженерні деталі") {
                        val count = turbinesCountInput.trim().toIntOrNull()
                        if (count == null || count < 1) {
                            turbinesError = "Вкажіть цілу кількість турбін (від 1)"
                        } else {
                            navController.navigate(Screen.WindDetails.createRoute(count))
                        }
                    }
                    MenuButton("До моніторингу") {
                        navController.navigate(Screen.Dashboard.route)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ForecastEditorDialog(
            initial = null,
            stations = state.stations,
            defaultStationId = state.selectedStationId ?: state.stations.firstOrNull()?.id ?: 1,
            onDismiss = { showAddDialog = false },
            onConfirm = { stationId, date, wind, power, temp, turb, notes ->
                viewModel.addForecast(stationId, date, wind, power, temp, turb, notes)
                showAddDialog = false
            }
        )
    }

    editingForecast?.let { forecast ->
        ForecastEditorDialog(
            initial = forecast,
            stations = state.stations,
            defaultStationId = forecast.stationId,
            onDismiss = { editingForecast = null },
            onConfirm = { stationId, date, wind, power, temp, turb, notes ->
                viewModel.updateForecast(
                    forecast.copy(
                        stationId = stationId,
                        date = date,
                        windSpeedMps = wind,
                        predictedPowerMw = power,
                        temperatureC = temp,
                        turbulencePercent = turb,
                        notes = notes
                    )
                )
                editingForecast = null
            }
        )
    }

    deletingForecast?.let { target ->
        ConfirmDialog(
            title = "Видалити прогноз за ${target.date}?",
            text = "Запис #${target.id} буде видалено із сервера та прибрано з локального кешу Room.",
            confirmLabel = "Видалити",
            onConfirm = {
                viewModel.deleteForecast(target.id)
                deletingForecast = null
            },
            onDismiss = { deletingForecast = null }
        )
    }
}

@Composable
private fun StationFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) CyanAccent else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ForecastItemCard(
    forecast: WindForecastDto,
    stationName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when {
        forecast.windSpeedMps < 3.0 -> AmberWarning
        forecast.windSpeedMps > 25.0 -> StormAlert
        else -> EmeraldLight
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${forecast.date} • $stationName",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    forecast.notes?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                StatusChip(
                    text = "${fmtMax2(forecast.predictedPowerMw)} МВт",
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💨 ${fmtMax2(forecast.windSpeedMps)} м/с  •  🌡 ${fmtMax2(forecast.temperatureC)}°C  •  🌀 ${forecast.turbulencePercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanAccent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onEdit,
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text("✎") }
                    OutlinedButton(
                        onClick = onDelete,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StormAlert),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text("🗑") }
                }
            }
        }
    }
}

@Composable
private fun ForecastEditorDialog(
    initial: WindForecastDto?,
    stations: List<RemoteWindStationDto>,
    defaultStationId: Int,
    onDismiss: () -> Unit,
    onConfirm: (
        stationId: Int,
        date: String,
        windSpeedMps: Double,
        predictedPowerMw: Double,
        temperatureC: Double,
        turbulencePercent: Int,
        notes: String?
    ) -> Unit
) {
    var selectedStationId by remember { mutableStateOf(initial?.stationId ?: defaultStationId) }
    var dateInput by remember { mutableStateOf(initial?.date ?: "2026-11-26") }
    var windInput by remember { mutableStateOf(initial?.windSpeedMps?.toString() ?: "10.5") }
    var powerInput by remember { mutableStateOf(initial?.predictedPowerMw?.toString() ?: "82.0") }
    var tempInput by remember { mutableStateOf(initial?.temperatureC?.toString() ?: "8.0") }
    var turbInput by remember { mutableStateOf(initial?.turbulencePercent?.toString() ?: "12") }
    var notesInput by remember { mutableStateOf(initial?.notes ?: "") }
    var attempted by remember { mutableStateOf(false) }

    val windVal = windInput.toDoubleOrNull()
    val powerVal = powerInput.toDoubleOrNull()
    val tempVal = tempInput.toDoubleOrNull()
    val turbVal = turbInput.toIntOrNull()

    val dateError = if (attempted && dateInput.isBlank()) "Вкажіть дату (YYYY-MM-DD)" else null
    val windError = if (attempted && (windVal == null || windVal < 0.0 || windVal > 45.0)) {
        "Швидкість вітру 0..45 м/с"
    } else null
    val powerError = if (attempted && (powerVal == null || powerVal < 0.0)) {
        "Введіть невід'ємне значення потужності"
    } else null
    val tempError = if (attempted && (tempVal == null || tempVal < -50.0 || tempVal > 60.0)) {
        "Температура -50..60 °C"
    } else null
    val turbError = if (attempted && (turbVal == null || turbVal !in 0..100)) {
        "Турбулентність 0..100 %"
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = if (initial == null) "Новий прогноз ВЕС" else "Редагування прогнозу #${initial.id}",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (initial == null && stations.isNotEmpty()) {
                    Text(
                        text = "Станція ВЕС:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        stations.forEach { station ->
                            StationFilterChip(
                                label = station.name,
                                selected = selectedStationId == station.id,
                                onClick = { selectedStationId = station.id }
                            )
                        }
                    }
                }
                AppTextField(
                    value = dateInput,
                    onValueChange = { dateInput = it },
                    label = "Дата прогнозу (YYYY-MM-DD)",
                    errorMessage = dateError
                )
                AppTextField(
                    value = windInput,
                    onValueChange = { windInput = it },
                    label = "Швидкість вітру (м/с)",
                    errorMessage = windError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                AppTextField(
                    value = powerInput,
                    onValueChange = { powerInput = it },
                    label = "Прогнозна потужність (МВт)",
                    errorMessage = powerError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                AppTextField(
                    value = tempInput,
                    onValueChange = { tempInput = it },
                    label = "Температура (°C)",
                    errorMessage = tempError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                AppTextField(
                    value = turbInput,
                    onValueChange = { turbInput = it },
                    label = "Турбулентність (%)",
                    errorMessage = turbError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                AppTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = "Нотатки диспетчера (опційно)"
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                attempted = true
                if (
                    dateInput.isNotBlank() &&
                    windVal != null && windVal in 0.0..45.0 &&
                    powerVal != null && powerVal >= 0.0 &&
                    tempVal != null && tempVal in -50.0..60.0 &&
                    turbVal != null && turbVal in 0..100
                ) {
                    onConfirm(
                        selectedStationId,
                        dateInput.trim(),
                        windVal,
                        powerVal,
                        tempVal,
                        turbVal,
                        notesInput.trim().ifBlank { null }
                    )
                }
            }) {
                Text(if (initial == null) "Додати" else "Зберегти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати")
            }
        }
    )
}

private fun fmt2(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun fmtMax2(value: Double): String {
    val tenths = value * 10.0
    val isSingleDecimal = abs(tenths - kotlin.math.round(tenths)) < 1e-6
    return String.format(Locale.US, if (isSingleDecimal) "%.1f" else "%.2f", value)
}
