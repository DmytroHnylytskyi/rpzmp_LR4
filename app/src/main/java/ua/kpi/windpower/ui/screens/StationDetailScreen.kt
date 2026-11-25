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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ua.kpi.windpower.data.geo.GeoDistanceCalculator
import ua.kpi.windpower.data.geo.WindPlantStatus
import ua.kpi.windpower.navigation.Screen
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
import ua.kpi.windpower.ui.viewmodels.WindStationsMapViewModel
import java.util.Locale

/**
 * Екран детальної інформації про обрану вітрову електростанцію (ПЗ №14 та №16).
 *
 * Відкривається при переході з картки списку `LazyColumn` або з маркера карти
 * за маршрутом `station_detail/{stationId}`.
 */
@Composable
fun StationDetailScreen(
    navController: NavHostController,
    stationId: Int,
    viewModel: WindStationsMapViewModel = viewModel()
) {
    val allStations by viewModel.allStations.collectAsState()
    val userPosition by viewModel.userPosition.collectAsState()

    val station = allStations.firstOrNull { it.id == stationId }
    if (station == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            EmptyState(
                iconText = "🧭",
                title = "Станцію #$stationId не знайдено",
                subtitle = "Поверніться до геопросторового реєстру ВЕС для вибору іншого об'єкта."
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Назад до карти ВЕС")
            }
        }
        return
    }

    val statusColor = when (station.status) {
        WindPlantStatus.ACTIVE -> EmeraldLight
        WindPlantStatus.PEAK -> AmberWarning
        WindPlantStatus.MAINTENANCE -> StormAlert
    }

    val distanceKm = GeoDistanceCalculator.distanceKm(
        userPosition.latitude,
        userPosition.longitude,
        station.latitude,
        station.longitude
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle(station.name)

        StatCard(
            title = "Поточна віддача в мережу",
            value = String.format(Locale.US, "%.1f", station.currentPowerMw),
            unit = "МВт",
            accent = statusColor,
            badge = { StatusChip(text = station.status.title, color = statusColor) },
            caption = "Встановлена потужність: ${String.format(Locale.US, "%.1f", station.capacityMw)} МВт " +
                "(КВВП ${String.format(Locale.US, "%.1f", station.loadFactorPercent)}%)"
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Телеметричні показники об'єкта",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    TelemetryMetricCard(
                        label = "Швидкість вітру",
                        value = "${String.format(Locale.US, "%.1f", station.windSpeedMps)} м/с",
                        accentColor = CyanAccent
                    )
                    TelemetryMetricCard(
                        label = "Кількість ВЕУ",
                        value = "${station.turbinesCount} шт",
                        accentColor = EmeraldLight
                    )
                    TelemetryMetricCard(
                        label = "Відстань GPS",
                        value = "${String.format(Locale.US, "%.1f", distanceKm)} км",
                        accentColor = AmberWarning
                    )
                }
            }
        }

        SectionHeader(title = "Паспортні та геопросторові дані")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Тип розміщення:", station.type.title)
                InfoRow("Регіон:", station.region)
                InfoRow(
                    "Координати WGS-84:",
                    "${String.format(Locale.US, "%.4f", station.latitude)}°N, ${String.format(Locale.US, "%.4f", station.longitude)}°E"
                )
                InfoRow("Позиція диспетчера:", userPosition.source.title())
                InfoRow("Останнє оновлення:", station.lastUpdated)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = station.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        MenuButton("Інженерний розрахунок (${station.turbinesCount} турбін)") {
            navController.navigate(Screen.WindDetails.createRoute(station.turbinesCount))
        }

        OutlinedButton(
            onClick = { navController.popBackStack() },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("← Назад до карти та списку")
        }
    }
}
