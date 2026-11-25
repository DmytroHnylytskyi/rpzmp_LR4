package ua.kpi.windpower.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import ua.kpi.windpower.data.geo.GeoDistanceCalculator
import ua.kpi.windpower.data.geo.GeoLocationSource
import ua.kpi.windpower.data.geo.MinPowerFilter
import ua.kpi.windpower.data.geo.StationSortOption
import ua.kpi.windpower.data.geo.WindGeoStation
import ua.kpi.windpower.data.geo.WindPlantStatus
import ua.kpi.windpower.data.geo.WindPlantType
import ua.kpi.windpower.navigation.Screen
import ua.kpi.windpower.ui.components.EmptyState
import ua.kpi.windpower.ui.components.StatusChip
import ua.kpi.windpower.ui.components.WindGeoMapPanel
import ua.kpi.windpower.ui.theme.AmberWarning
import ua.kpi.windpower.ui.theme.CyanAccent
import ua.kpi.windpower.ui.theme.EmeraldLight
import ua.kpi.windpower.ui.theme.StormAlert
import ua.kpi.windpower.ui.viewmodels.MapRenderMode
import ua.kpi.windpower.ui.viewmodels.WindStationsMapViewModel
import java.util.Locale

/**
 * Екран динамічного списку, багаторівневої фільтрації, GPS-моніторингу та інтерактивної карти ВЕС
 * (об'єднання ПЗ №14, №15 та №16 на єдиному просунутому рівні).
 *
 * Забезпечує:
 * - пошук та реактивне комбіноване сортування/фільтрацію (`Flow combine` + `debounce` + `derivedStateOf`);
 * - динамічний список карток [LazyColumn] з програмним прокручуванням до обраного маркера;
 * - двосторонню синхронізацію між `LazyColumn` і маркерами OpenStreetMap / векторного радара;
 * - роботу з GPS-геолокацією через Android [android.location.LocationManager] із обробкою відмови дозволу.
 */
@Composable
fun StationsMapScreen(
    navController: NavHostController,
    viewModel: WindStationsMapViewModel
) {
    val allStations by viewModel.allStations.collectAsState()
    val filteredStations by viewModel.filteredStations.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterCriteria by viewModel.filterCriteria.collectAsState()
    val userPosition by viewModel.userPosition.collectAsState()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsState()
    val selectedStationId by viewModel.selectedStationId.collectAsState()
    val mapRenderMode by viewModel.mapRenderMode.collectAsState()
    val cameraFocusVersion by viewModel.cameraFocusVersion.collectAsState()
    val cameraFocusTarget by viewModel.cameraFocusTarget.collectAsState()

    var showFiltersPanel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Використання derivedStateOf для обчислення активної кількості та сумарної генерації видимих ВЕС
    val totalFilteredPowerMw by remember(filteredStations) {
        derivedStateOf { filteredStations.sumOf { it.currentPowerMw } }
    }
    val activeFilterCount by remember(filterCriteria) {
        derivedStateOf {
            listOfNotNull(
                filterCriteria.type,
                filterCriteria.status,
                filterCriteria.minPower.takeIf { it != MinPowerFilter.ALL }
            ).size
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            viewModel.startLocationCollection()
        }
    }

    // Двостороння синхронізація: при виборі маркера на карті LazyColumn автоматично прокручується до картки
    LaunchedEffect(selectedStationId, filteredStations) {
        val targetId = selectedStationId ?: return@LaunchedEffect
        val index = filteredStations.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- 1. Компактний заголовок, режим карти та лічильник об'єктів ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Карта та реєстр ВЕС",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Показано ${filteredStations.size}/${allStations.size} • ${String.format(Locale.US, "%.1f", totalFilteredPowerMw)} МВт",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanAccent
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { viewModel.refreshLiveWinds() },
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("↻ Вітер", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        val next = if (mapRenderMode == MapRenderMode.OSM) {
                            MapRenderMode.VECTOR_RADAR
                        } else {
                            MapRenderMode.OSM
                        }
                        viewModel.setMapRenderMode(next)
                    },
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (mapRenderMode == MapRenderMode.OSM) "📡 Радар" else "🗺 OSM",
                        fontSize = 11.sp
                    )
                }
            }
        }

        // --- 2. Пошук (ПЗ №15) + кнопка розгортання багаторівневих фільтрів та GPS ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                placeholder = { Text("Пошук ВЕС, області або опису...", fontSize = 13.sp) },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Text("✕")
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { showFiltersPanel = !showFiltersPanel },
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (filterCriteria.hasActiveFilters || !hasLocationPermission) AmberWarning
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Text(
                    text = buildString {
                        append("Фільтри")
                        if (activeFilterCount > 0) append(" ($activeFilterCount)")
                        append(if (showFiltersPanel) " ▲" else " ▼")
                    },
                    fontSize = 12.sp
                )
            }
        }

        // --- 3. Банер статусу GPS або попередження про відсутність дозволу (ПЗ №16) ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (hasLocationPermission) MaterialTheme.colorScheme.surface
                else AmberWarning.copy(alpha = 0.12f)
            ),
            border = if (!hasLocationPermission) BorderStroke(1.dp, AmberWarning.copy(alpha = 0.45f)) else null,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📍 ${userPosition.source.title()} (${String.format(Locale.US, "%.2f", userPosition.latitude)}, ${String.format(Locale.US, "%.2f", userPosition.longitude)})",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (hasLocationPermission) EmeraldLight else AmberWarning,
                    modifier = Modifier.weight(1f)
                )
                if (!hasLocationPermission) {
                    TextButton(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Дозволити GPS", fontSize = 11.sp, color = CyanAccent)
                    }
                }
            }
        }

        // --- 4. Розширена панель багаторівневої фільтрації, сортування та тестового GPS ---
        AnimatedVisibility(visible = showFiltersPanel) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Тип ВЕС:", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GeoFilterChip("Усі типи", filterCriteria.type == null) {
                            viewModel.updateTypeFilter(null)
                        }
                        WindPlantType.entries.forEach { t ->
                            GeoFilterChip(t.title, filterCriteria.type == t) {
                                viewModel.updateTypeFilter(t)
                            }
                        }
                    }

                    Text("Статус та мінімальна потужність:", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GeoFilterChip("Будь-який статус", filterCriteria.status == null) {
                            viewModel.updateStatusFilter(null)
                        }
                        WindPlantStatus.entries.forEach { st ->
                            GeoFilterChip(st.title, filterCriteria.status == st) {
                                viewModel.updateStatusFilter(st)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MinPowerFilter.entries.forEach { p ->
                            GeoFilterChip(p.label, filterCriteria.minPower == p) {
                                viewModel.updateMinPowerFilter(p)
                            }
                        }
                    }

                    Text("Сортування:", style = MaterialTheme.typography.labelSmall, color = CyanAccent)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StationSortOption.entries.forEach { opt ->
                            GeoFilterChip(opt.title, filterCriteria.sortOption == opt) {
                                viewModel.updateSortOption(opt)
                            }
                        }
                    }

                    val currentSource = userPosition.source
                    val selectedCity = (currentSource as? GeoLocationSource.SimulatedPreset)?.cityName
                    val isRealGpsActive = currentSource is GeoLocationSource.GpsSensor ||
                        currentSource is GeoLocationSource.NetworkProvider
                    Text("Пункт базування диспетчера (GPS):", style = MaterialTheme.typography.labelSmall, color = AmberWarning)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GeoFilterChip("Київ (КПІ)", selectedCity?.startsWith("Київ") == true) {
                            viewModel.selectSimulatedDispatcherCity("Київ (НТУУ «КПІ»)", 50.4494, 30.4573)
                        }
                        GeoFilterChip("Миколаїв", selectedCity == "Миколаїв") {
                            viewModel.selectSimulatedDispatcherCity("Миколаїв", 46.9750, 31.9946)
                        }
                        GeoFilterChip("Львів", selectedCity == "Львів") {
                            viewModel.selectSimulatedDispatcherCity("Львів", 49.8397, 24.0297)
                        }
                        if (hasLocationPermission) {
                            GeoFilterChip("Реальний GPS", isRealGpsActive) {
                                viewModel.startLocationCollection()
                                viewModel.focusOnUserLocation()
                            }
                        }
                    }

                    if (filterCriteria.hasActiveFilters || searchQuery.isNotEmpty()) {
                        TextButton(onClick = viewModel::resetAllFilters) {
                            Text("Скинути всі фільтри й пошук", color = StormAlert)
                        }
                    }
                }
            }
        }

        // --- 5. Адаптивний макет: Карта + Динамічний список LazyColumn із двосторонньою синхронізацією ---
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val isWideLayout = maxWidth > 650.dp

            if (isWideLayout) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StationsLazyListPane(
                        stations = filteredStations,
                        selectedStationId = selectedStationId,
                        userLat = userPosition.latitude,
                        userLon = userPosition.longitude,
                        listState = listState,
                        onSelectStation = { id -> viewModel.selectStation(id, focusMap = true) },
                        onOpenDetails = { id -> navController.navigate(Screen.StationDetail.createRoute(id)) },
                        onResetFilters = viewModel::resetAllFilters,
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight()
                    )
                    WindGeoMapPanel(
                        stations = filteredStations,
                        selectedStationId = selectedStationId,
                        userPosition = userPosition,
                        renderMode = mapRenderMode,
                        cameraFocusVersion = cameraFocusVersion,
                        cameraFocusTarget = cameraFocusTarget,
                        onSelectStation = { id -> viewModel.selectStation(id, focusMap = false) },
                        onOpenDetails = { id -> navController.navigate(Screen.StationDetail.createRoute(id)) },
                        onFocusUser = viewModel::focusOnUserLocation,
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WindGeoMapPanel(
                        stations = filteredStations,
                        selectedStationId = selectedStationId,
                        userPosition = userPosition,
                        renderMode = mapRenderMode,
                        cameraFocusVersion = cameraFocusVersion,
                        cameraFocusTarget = cameraFocusTarget,
                        onSelectStation = { id -> viewModel.selectStation(id, focusMap = false) },
                        onOpenDetails = { id -> navController.navigate(Screen.StationDetail.createRoute(id)) },
                        onFocusUser = viewModel::focusOnUserLocation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(245.dp)
                    )
                    StationsLazyListPane(
                        stations = filteredStations,
                        selectedStationId = selectedStationId,
                        userLat = userPosition.latitude,
                        userLon = userPosition.longitude,
                        listState = listState,
                        onSelectStation = { id -> viewModel.selectStation(id, focusMap = true) },
                        onOpenDetails = { id -> navController.navigate(Screen.StationDetail.createRoute(id)) },
                        onResetFilters = viewModel::resetAllFilters,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StationsLazyListPane(
    stations: List<WindGeoStation>,
    selectedStationId: Int?,
    userLat: Double,
    userLon: Double,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSelectStation: (Int) -> Unit,
    onOpenDetails: (Int) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (stations.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    iconText = "🛰",
                    title = "Станції не знайдені",
                    subtitle = "Жоден енергетичний об'єкт не відповідає поточному пошуку чи фільтрам."
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = onResetFilters) {
                    Text("Скинути фільтри")
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(stations, key = { it.id }) { station ->
            val isSelected = station.id == selectedStationId
            val statusColor = when (station.status) {
                WindPlantStatus.ACTIVE -> EmeraldLight
                WindPlantStatus.PEAK -> AmberWarning
                WindPlantStatus.MAINTENANCE -> StormAlert
            }
            val distanceKm = GeoDistanceCalculator.distanceKm(
                userLat,
                userLon,
                station.latitude,
                station.longitude
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                ),
                border = if (isSelected) BorderStroke(1.5.dp, CyanAccent) else null,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectStation(station.id) }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = station.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) CyanAccent else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${station.type.title} • ${station.region}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusChip(text = station.status.title, color = statusColor)
                    }

                    Text(
                        text = station.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ ${String.format(Locale.US, "%.1f", station.currentPowerMw)}/${String.format(Locale.US, "%.0f", station.capacityMw)} МВт • " +
                                "💨 ${String.format(Locale.US, "%.1f", station.windSpeedMps)} м/с • " +
                                "📏 ${String.format(Locale.US, "%.1f", distanceKm)} км",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldLight,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onOpenDetails(station.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Деталі →", fontSize = 11.sp, color = CyanAccent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeoFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) CyanAccent else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(text = label, fontSize = 11.sp)
    }
}
