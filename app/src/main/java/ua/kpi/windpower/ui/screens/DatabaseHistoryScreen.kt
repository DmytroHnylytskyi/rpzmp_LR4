package ua.kpi.windpower.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ua.kpi.windpower.data.db.WindStationSeedCatalog
import ua.kpi.windpower.data.db.entities.WindStation
import ua.kpi.windpower.ui.components.AppTextField
import ua.kpi.windpower.ui.components.ConfirmDialog
import ua.kpi.windpower.ui.components.EmptyState
import ua.kpi.windpower.ui.components.InfoRow
import ua.kpi.windpower.ui.components.ScreenTitle
import ua.kpi.windpower.ui.components.SectionHeader
import ua.kpi.windpower.ui.components.StatusChip
import ua.kpi.windpower.ui.theme.AmberWarning
import ua.kpi.windpower.ui.theme.CyanAccent
import ua.kpi.windpower.ui.theme.EmeraldLight
import ua.kpi.windpower.ui.theme.StormAlert
import ua.kpi.windpower.ui.viewmodels.AddStationResult
import ua.kpi.windpower.ui.viewmodels.WindDatabaseViewModel
import java.util.Locale

private enum class StationSort(val label: String) {
    BY_NAME("За назвою"),
    BY_CAPACITY("За потужністю")
}

private val PLANT_TYPE_OPTIONS = listOf("Наземна ВЕС", "Прибережна ВЕС", "Гібрид ВЕС+АКБ")
private val PLANT_STATUS_OPTIONS = listOf("Активна", "Пікове навантаження", "Обслуговування")

/**
 * Екран керування локальним реляційним сховищем станцій ВЕС (Room SQLite).
 *
 * Забезпечує:
 * - перегляд і реактивний пошук/сортування станцій із єдиної БД проєкту;
 * - створення, редагування та каскадне видалення вітрових електростанцій;
 * - дозапис нових телеметричних вимірів та SQL-агрегацію середніх показників (AVG);
 * - відновлення базового довідника реальних ВЕС України за порожньої бази.
 */
@Composable
fun DatabaseHistoryScreen(
    onNavigateToRegisterStation: (() -> Unit)? = null,
    viewModel: WindDatabaseViewModel = viewModel()
) {
    val stations by viewModel.stations.collectAsState()
    val selectedStationId by viewModel.selectedStationId.collectAsState()
    val analytics by viewModel.analytics.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(StationSort.BY_NAME) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingStation by remember { mutableStateOf<WindStation?>(null) }
    var telemetryTargetStation by remember { mutableStateOf<WindStation?>(null) }
    var stationToDelete by remember { mutableStateOf<WindStation?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val filteredStations = remember(stations, searchQuery, sort) {
        val q = searchQuery.trim()
        val matched = if (q.isEmpty()) stations else stations.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.location.contains(q, ignoreCase = true) ||
                it.plantType.contains(q, ignoreCase = true)
        }
        when (sort) {
            StationSort.BY_NAME -> matched.sortedBy { it.name }
            StationSort.BY_CAPACITY -> matched.sortedByDescending { it.installedCapacityMw }
        }
    }

    val selectedStation = stations.firstOrNull { it.id == selectedStationId }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenTitle("Реляційна база даних ВЕС")
            }

            // --- Панель швидкого додавання ВЕС ---
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCreateDialog = true },
                        enabled = !isSaving,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("＋ Додати ВЕС у БД")
                    }
                    if (onNavigateToRegisterStation != null) {
                        OutlinedButton(
                            onClick = onNavigateToRegisterStation,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📝 Анкета реєстрації")
                        }
                    }
                }
            }

            // --- Пошук та сортування ---
            item {
                SectionHeader(
                    title = "Зареєстровані станції ВЕС",
                    trailing = "${filteredStations.size} з ${stations.size}"
                )
                Spacer(modifier = Modifier.height(4.dp))
                AppTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = "Пошук станції (назва, область, тип)"
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StationSort.entries.forEach { option ->
                        FilterSortChip(
                            label = option.label,
                            isSelected = sort == option,
                            modifier = Modifier.weight(1f)
                        ) { sort = option }
                    }
                }
            }

            // --- Список станцій або Empty State ---
            if (filteredStations.isEmpty()) {
                item {
                    EmptyState(
                        title = if (searchQuery.isBlank() && stations.isEmpty()) {
                            "База даних порожня"
                        } else {
                            "Нічого не знайдено"
                        },
                        subtitle = if (searchQuery.isBlank() && stations.isEmpty()) {
                            "Додайте станцію вручну або завантажте початковий довідник українських ВЕС."
                        } else {
                            "Спробуйте змінити пошуковий запит або скинути фільтр."
                        },
                        iconText = if (searchQuery.isBlank()) "🗄️" else "🔍"
                    )
                    if (stations.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                viewModel.restoreDefaultStations { count ->
                                    showSnackbar("✓ Завантажено $count станцій ВЕС у Room БД")
                                }
                            },
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("↻ Завантажити каталог ВЕС України")
                        }
                    }
                }
            } else {
                items(filteredStations, key = { it.id }) { station ->
                    StationCard(
                        station = station,
                        isSelected = station.id == selectedStationId,
                        onClick = { viewModel.selectStation(station.id) },
                        onEditRequest = { editingStation = station },
                        onDeleteRequest = { stationToDelete = station }
                    )
                }

                // --- Аналітика обраної станції (SQL AVG + історія замірів) ---
                selectedStation?.let { station ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Аналітика: ${station.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = EmeraldLight,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(
                                        onClick = { telemetryTargetStation = station },
                                        shape = MaterialTheme.shapes.small,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("＋ Новий вимір", fontSize = 11.sp, color = CyanAccent)
                                    }
                                }

                                InfoRow("Тип / статус:", "${station.plantType} • ${station.status}")
                                InfoRow(
                                    "Координати (WGS-84):",
                                    "${String.format(Locale.US, "%.4f", station.latitude)}°N, ${String.format(Locale.US, "%.4f", station.longitude)}°E"
                                )
                                analytics.averagePower?.let {
                                    InfoRow(
                                        "Середня вироблена потужність (AVG):",
                                        String.format(Locale.US, "%.2f МВт", it)
                                    )
                                } ?: InfoRow("Середня вироблена потужність (AVG):", "—")
                                analytics.averageSpeed?.let {
                                    InfoRow(
                                        "Середня швидкість вітру (AVG):",
                                        String.format(Locale.US, "%.2f м/с", it)
                                    )
                                } ?: InfoRow("Середня швидкість вітру (AVG):", "—")

                                Spacer(modifier = Modifier.height(6.dp))
                                HistorySection("Історія потужностей (DESC):", analytics.powers.map { it.value }, "МВт")
                                HistorySection("Історія швидкостей вітру (DESC):", analytics.speeds.map { it.value }, "м/с")
                                HistorySection("Вихідні напруги генераторів:", analytics.voltages.map { it.value }, "В")
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    // Діалог створення нової ВЕС у Room
    if (showCreateDialog) {
        StationEditorDialog(
            initial = null,
            onDismiss = { showCreateDialog = false },
            onSave = { draft, powers, speeds, voltages ->
                viewModel.addStationWithTelemetry(
                    name = draft.name,
                    location = draft.location,
                    capacity = draft.installedCapacityMw,
                    turbinesCount = draft.turbinesCount,
                    plantType = draft.plantType,
                    status = draft.status,
                    latitude = draft.latitude,
                    longitude = draft.longitude,
                    description = draft.description,
                    powers = powers,
                    speeds = speeds,
                    voltages = voltages
                ) { result ->
                    when (result) {
                        is AddStationResult.Success -> {
                            showCreateDialog = false
                            showSnackbar("✓ ВЕС «${draft.name}» збережено у Room БД")
                        }
                        is AddStationResult.Error -> showSnackbar("✗ ${result.message}")
                    }
                }
            }
        )
    }

    // Діалог редагування існуючої ВЕС у Room
    editingStation?.let { target ->
        StationEditorDialog(
            initial = target,
            onDismiss = { editingStation = null },
            onSave = { updated, _, _, _ ->
                viewModel.updateStation(updated.copy(id = target.id)) { result ->
                    when (result) {
                        is AddStationResult.Success -> {
                            editingStation = null
                            showSnackbar("✓ Параметри «${updated.name}» оновлено в БД")
                        }
                        is AddStationResult.Error -> showSnackbar("✗ ${result.message}")
                    }
                }
            }
        )
    }

    // Діалог дозапису замірів телеметрії для обраної станції
    telemetryTargetStation?.let { target ->
        AddTelemetryDialog(
            station = target,
            onDismiss = { telemetryTargetStation = null },
            onSave = { powers, speeds, voltages ->
                viewModel.addTelemetryMeasurements(
                    stationId = target.id,
                    powers = powers,
                    speeds = speeds,
                    voltages = voltages
                ) { result ->
                    when (result) {
                        is AddStationResult.Success -> {
                            telemetryTargetStation = null
                            showSnackbar("✓ Нові виміри додано до «${target.name}»")
                        }
                        is AddStationResult.Error -> showSnackbar("✗ ${result.message}")
                    }
                }
            }
        )
    }

    // Діалог підтвердження каскадного видалення станції
    stationToDelete?.let { station ->
        ConfirmDialog(
            title = "Видалити ${station.name}?",
            text = "Разом зі станцією буде каскадно видалено всі її записи телеметрії (потужності, швидкості вітру, напруги). Дію неможливо скасувати.",
            confirmLabel = "Видалити",
            onConfirm = {
                viewModel.deleteStation(station) { deleted ->
                    showSnackbar("✗ ${deleted.name} та її телеметрія видалені з БД")
                }
                stationToDelete = null
            },
            onDismiss = { stationToDelete = null }
        )
    }
}

@Composable
private fun StationEditorDialog(
    initial: WindStation?,
    onDismiss: () -> Unit,
    onSave: (
        station: WindStation,
        powers: List<Double>,
        speeds: List<Double>,
        voltages: List<Double>
    ) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "Миколаївська обл.") }
    var capacityInput by remember { mutableStateOf(initial?.installedCapacityMw?.toString() ?: "120.0") }
    var turbinesInput by remember { mutableStateOf(initial?.turbinesCount?.toString() ?: "20") }
    var plantType by remember { mutableStateOf(initial?.plantType ?: PLANT_TYPE_OPTIONS.first()) }
    var status by remember { mutableStateOf(initial?.status ?: PLANT_STATUS_OPTIONS.first()) }
    var latInput by remember { mutableStateOf(initial?.latitude?.toString() ?: "") }
    var lonInput by remember { mutableStateOf(initial?.longitude?.toString() ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var powersInput by remember { mutableStateOf("88.5, 96.2") }
    var speedsInput by remember { mutableStateOf("10.2, 11.4") }
    var voltagesInput by remember { mutableStateOf("690.0, 691.0") }
    var attempted by remember { mutableStateOf(false) }

    val capVal = capacityInput.trim().toDoubleOrNull()
    val turbVal = turbinesInput.trim().toIntOrNull()
    val parsedLat = latInput.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val parsedLon = lonInput.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    val nameError = when {
        !attempted -> null
        name.trim().length < 3 -> "Назва має містити щонайменше 3 символи"
        else -> null
    }
    val capError = if (attempted && (capVal == null || capVal <= 0.0)) "Введіть додатну потужність" else null
    val turbError = if (attempted && (turbVal == null || turbVal < 1)) "Вкажіть ≥ 1 турбіну" else null
    val latError = if (attempted && latInput.isNotBlank() && (parsedLat == null || parsedLat !in -90.0..90.0)) {
        "Широта -90..90"
    } else null
    val lonError = if (attempted && lonInput.isNotBlank() && (parsedLon == null || parsedLon !in -180.0..180.0)) {
        "Довгота -180..180"
    } else null

    val (powersList, powersErr) = parseDoubleList(powersInput)
    val (speedsList, speedsErr) = parseDoubleList(speedsInput)
    val (voltagesList, voltagesErr) = parseDoubleList(voltagesInput)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = if (initial == null) "Нова станція ВЕС" else "Редагувати ${initial.name}",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppTextField(value = name, onValueChange = { name = it }, label = "Назва станції", errorMessage = nameError)
                AppTextField(value = location, onValueChange = { location = it }, label = "Регіон / область")
                AppTextField(
                    value = capacityInput,
                    onValueChange = { capacityInput = it },
                    label = "Встановлена потужність (МВт)",
                    errorMessage = capError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                AppTextField(
                    value = turbinesInput,
                    onValueChange = { turbinesInput = it.filter { ch -> ch.isDigit() } },
                    label = "Кількість турбін (ВЕУ)",
                    errorMessage = turbError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text("Тип розміщення:", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PLANT_TYPE_OPTIONS.forEach { opt ->
                        FilterSortChip(label = opt, isSelected = plantType == opt) { plantType = opt }
                    }
                }

                Text("Статус об'єкта:", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PLANT_STATUS_OPTIONS.forEach { opt ->
                        FilterSortChip(label = opt, isSelected = status == opt) { status = opt }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(
                        value = latInput,
                        onValueChange = { latInput = it },
                        label = "Широта (авто за обл.)",
                        errorMessage = latError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    AppTextField(
                        value = lonInput,
                        onValueChange = { lonInput = it },
                        label = "Довгота (авто за обл.)",
                        errorMessage = lonError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Технічний опис (опційно)"
                )

                if (initial == null) {
                    AppTextField(
                        value = powersInput,
                        onValueChange = { powersInput = it },
                        label = "Початкові потужності (МВт через кому)",
                        errorMessage = if (attempted && (powersErr != null || powersList.isEmpty())) powersErr ?: "Потрібен хоча б 1 вимір" else null
                    )
                    AppTextField(
                        value = speedsInput,
                        onValueChange = { speedsInput = it },
                        label = "Швидкості вітру (м/с через кому)",
                        errorMessage = if (attempted && (speedsErr != null || speedsList.isEmpty())) speedsErr ?: "Потрібен хоча б 1 вимір" else null
                    )
                    AppTextField(
                        value = voltagesInput,
                        onValueChange = { voltagesInput = it },
                        label = "Напруги генераторів (В через кому)",
                        errorMessage = if (attempted) voltagesErr else null
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                attempted = true
                val coordOk = latError == null && lonError == null
                val arraysOk = initial != null || (powersErr == null && speedsErr == null && voltagesErr == null && powersList.isNotEmpty() && speedsList.isNotEmpty())
                if (name.trim().length >= 3 && capVal != null && capVal > 0.0 && turbVal != null && turbVal >= 1 && coordOk && arraysOk) {
                    val cleanLoc = location.trim().ifBlank { "Україна" }
                    val (approxLat, approxLon) = WindStationSeedCatalog.approximateRegionCoordinates(cleanLoc)
                    onSave(
                        WindStation(
                            id = initial?.id ?: 0,
                            name = name.trim(),
                            location = cleanLoc,
                            installedCapacityMw = capVal,
                            turbinesCount = turbVal,
                            plantType = plantType,
                            status = status,
                            latitude = parsedLat ?: approxLat,
                            longitude = parsedLon ?: approxLon,
                            description = description.trim()
                        ),
                        powersList,
                        speedsList,
                        voltagesList
                    )
                }
            }) {
                Text(if (initial == null) "Зберегти" else "Оновити")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )
}

@Composable
private fun AddTelemetryDialog(
    station: WindStation,
    onDismiss: () -> Unit,
    onSave: (powers: List<Double>, speeds: List<Double>, voltages: List<Double>) -> Unit
) {
    var powersInput by remember { mutableStateOf(String.format(Locale.US, "%.1f", station.installedCapacityMw * 0.78)) }
    var speedsInput by remember { mutableStateOf("10.9") }
    var voltagesInput by remember { mutableStateOf("690.5") }
    var attempted by remember { mutableStateOf(false) }

    val (powersList, powersErr) = parseDoubleList(powersInput)
    val (speedsList, speedsErr) = parseDoubleList(speedsInput)
    val (voltagesList, voltagesErr) = parseDoubleList(voltagesInput)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Нові виміри: ${station.name}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppTextField(
                    value = powersInput,
                    onValueChange = { powersInput = it },
                    label = "Потужність (МВт, через кому)",
                    errorMessage = if (attempted) powersErr else null
                )
                AppTextField(
                    value = speedsInput,
                    onValueChange = { speedsInput = it },
                    label = "Швидкість вітру (м/с, через кому)",
                    errorMessage = if (attempted) speedsErr else null
                )
                AppTextField(
                    value = voltagesInput,
                    onValueChange = { voltagesInput = it },
                    label = "Напруга (В, через кому)",
                    errorMessage = if (attempted) voltagesErr else null
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                attempted = true
                if (powersErr == null && speedsErr == null && voltagesErr == null &&
                    (powersList.isNotEmpty() || speedsList.isNotEmpty() || voltagesList.isNotEmpty())
                ) {
                    onSave(powersList, speedsList, voltagesList)
                }
            }) {
                Text("Записати в БД")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )
}

private fun parseDoubleList(raw: String): Pair<List<Double>, String?> {
    val tokens = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val values = tokens.mapNotNull { it.toDoubleOrNull() }
    return if (tokens.isNotEmpty() && values.size != tokens.size) {
        values to "Частина значень не розпізнана як число"
    } else values to null
}

@Composable
private fun FilterSortChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StationCard(
    station: WindStation,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEditRequest: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val statusColor = when {
        station.status.contains("Піков", ignoreCase = true) -> AmberWarning
        station.status.contains("Обслугов", ignoreCase = true) -> StormAlert
        else -> EmeraldLight
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(1.5.dp, CyanAccent) else null,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "⚡ #${station.id} • ${station.name}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${station.location} • ${station.plantType} • ${String.format(Locale.US, "%.1f", station.installedCapacityMw)} МВт (${station.turbinesCount} ВЕУ)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(station.status, statusColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${String.format(Locale.US, "%.2f", station.latitude)}°N, ${String.format(Locale.US, "%.2f", station.longitude)}°E",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else CyanAccent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onEditRequest,
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("✎ Змінити", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onDeleteRequest,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StormAlert),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("🗑", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySection(title: String, values: List<Double>, unit: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (values.isEmpty()) {
        Text(
            text = "  • немає записів",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        values.forEach { value ->
            Text(
                text = "  • ${String.format(Locale.US, "%.2f", value)} $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}
