package ua.kpi.windpower.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windpower.data.stream.StreamHealthState
import ua.kpi.windpower.ui.components.InfoRow
import ua.kpi.windpower.ui.components.ScreenTitle
import ua.kpi.windpower.ui.components.SectionHeader
import ua.kpi.windpower.ui.components.StatusChip
import ua.kpi.windpower.ui.components.TelemetryMetricCard
import ua.kpi.windpower.ui.theme.AmberWarning
import ua.kpi.windpower.ui.theme.CyanAccent
import ua.kpi.windpower.ui.theme.EmeraldLight
import ua.kpi.windpower.ui.theme.StormAlert
import ua.kpi.windpower.ui.viewmodels.WindTelemetryViewModel
import java.util.Locale

/**
 * Екран оперативної та потокової SCADA-телеметрії ВЕС (ЛР №2 + ПЗ №13).
 *
 * Поєднує:
 * - потокову обробку даних у реальному часі на базі Kotlin Coroutines і Flow
 *   (холодні та гарячі потоки `Flow` / `SharedFlow` / `StateFlow`, трансформації `map`,
 *   фільтрація `filter`, згладжування `debounce`, комбінування `combine` та `zip`,
 *   відновлення після збоїв `retryWhen` та перехоплення помилок `catch`);
 * - дискретне ручне опитування польових контролерів SCADA зі збереженням стану (ЛР №2).
 */
@Composable
fun TelemetryMvvmScreen(
    viewModel: WindTelemetryViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.ensureStreamingStarted()
    }

    // Потокова телеметрія Coroutines & Flow (ПЗ №13)
    val isStreaming by viewModel.isStreaming.collectAsState()
    val combinedFrame by viewModel.combinedFrame.collectAsState()
    val zippedCalibrationDelta by viewModel.zippedCalibrationDelta.collectAsState()
    val streamHealth by viewModel.streamHealth.collectAsState()
    val rawMinWindInput by viewModel.rawMinWindInput.collectAsState()
    val debouncedMinWindMps by viewModel.debouncedMinWindMps.collectAsState()
    val recentFrames by viewModel.recentFrames.collectAsState()
    val alertHistory by viewModel.alertHistory.collectAsState()

    // Ручне опитування SCADA (ЛР №2)
    val power by viewModel.power.collectAsState()
    val windSpeed by viewModel.windSpeed.collectAsState()
    val status by viewModel.status.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val healthVisual = when (val health = streamHealth) {
        is StreamHealthState.Streaming -> "ПОТІК АКТИВНИЙ" to EmeraldLight
        is StreamHealthState.Paused -> "ПАУЗА" to AmberWarning
        is StreamHealthState.FilteredOut ->
            "ВІДСІЯНО (${fmt1(health.speedMps)} < ${fmt1(health.thresholdMps)})" to CyanAccent
        is StreamHealthState.Retrying ->
            "RETRY ${health.attempt}/${health.maxAttempts}" to AmberWarning
        is StreamHealthState.Fault -> "АВАРІЯ ПОТОКУ" to StormAlert
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle("SCADA Потоки та Телеметрія")

        // --- 1. Панель керування потоками Coroutines & Flow (ПЗ №13) ---
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ПОТОКОВА ТЕЛЕМЕТРІЯ АСДУ",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent
                        )
                        Text(
                            text = "Синхронізація давачів анемометрії та метеопрогнозу в реальному часі",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusChip(text = healthVisual.first, color = healthVisual.second)
                }

                // Банер детального стану retryWhen / filter / catch
                when (val health = streamHealth) {
                    is StreamHealthState.Retrying -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = AmberWarning.copy(alpha = 0.14f)
                            ),
                            border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.45f)),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⏳ Відновлення каналу зв'язку (${health.attempt}/${health.maxAttempts}): ${health.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberWarning,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    is StreamHealthState.Fault -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = StormAlert.copy(alpha = 0.14f)
                            ),
                            border = BorderStroke(1.dp, StormAlert.copy(alpha = 0.45f)),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✕ Аварія опитування телеметрії: ${health.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StormAlert,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    else -> Unit
                }

                // Кнопки управління потоком та тестування відмовостійкості
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::toggleStreaming,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isStreaming) "⏸ Пауза" else "▶ Запустити")
                    }
                    OutlinedButton(
                        onClick = { viewModel.triggerSimulatedSensorFault(2) },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Збій каналу")
                    }
                    OutlinedButton(
                        onClick = { viewModel.triggerSimulatedSensorFault(5) },
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StormAlert),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Обрив лінії")
                    }
                }
            }
        }

        // --- 2. Результат об'єднання потоків (combine + zip) ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val frame = combinedFrame
                if (frame == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Очікування синхронізації потоків...")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Кадр давача #${frame.sensor.tick} / NWP #${frame.forecast.tick}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        StatusChip(
                            text = "${fmt2(frame.combinedAveragePowerMw)} МВт (сер.)",
                            color = EmeraldLight
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        TelemetryMetricCard(
                            label = "Анемометр ВЕУ",
                            value = "${fmt2(frame.sensor.powerMw)} МВт",
                            accentColor = EmeraldLight
                        )
                        TelemetryMetricCard(
                            label = "Прогноз NWP",
                            value = "${fmt2(frame.forecast.expectedPowerMw)} МВт",
                            accentColor = CyanAccent
                        )
                        TelemetryMetricCard(
                            label = "Вітер давача",
                            value = "${fmt1(frame.sensor.rawSpeedMps)} м/с",
                            accentColor = AmberWarning
                        )
                    }

                    val sign = if (frame.powerDeviationMw >= 0.0) "+" else ""
                    InfoRow(
                        "Відхилення факт − прогноз:",
                        "$sign${fmt2(frame.powerDeviationMw)} МВт (${sign}${fmt1(frame.calibrationDeltaPercent)}%)"
                    )
                    InfoRow(
                        "Синхронне калібрування каналів:",
                        zippedCalibrationDelta?.let { d ->
                            val zSign = if (d >= 0.0) "+" else ""
                            "$zSign${fmt1(d)}%"
                        } ?: "синхронізація..."
                    )
                    InfoRow(
                        "Статус вітропарку:",
                        frame.sensor.status
                    )
                }
            }
        }

        // --- 3. Фільтрація та debounce потоку вітру ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Поріг зрізання шумів вітрового потоку",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "≥ ${fmt1(debouncedMinWindMps)} м/с",
                        style = MaterialTheme.typography.labelLarge,
                        color = CyanAccent
                    )
                }
                Slider(
                    value = rawMinWindInput.toFloat(),
                    onValueChange = { viewModel.updateMinWindFilter(it.toDouble()) },
                    valueRange = 0f..14f,
                    colors = SliderDefaults.colors(
                        thumbColor = CyanAccent,
                        activeTrackColor = CyanAccent
                    )
                )
                Text(
                    text = "Стабілізація уставки порогу виконується із затримкою 250 мс; слабкі пориви нижче порогу ігноруються.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- 4. Стрічка останніх кадрів та подій SharedFlow ---
        if (recentFrames.isNotEmpty()) {
            SectionHeader(
                title = "Останні кадри потоку",
                trailing = "${recentFrames.size} у буфері"
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                recentFrames.take(4).forEach { reading ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${reading.tick} • ${fmt1(reading.rawSpeedMps)} м/с • ${reading.turbulencePercent}% турб.",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${fmt2(reading.powerMw)} МВт",
                                style = MaterialTheme.typography.labelLarge,
                                color = EmeraldLight
                            )
                        }
                    }
                }
            }
        }

        if (alertHistory.isNotEmpty()) {
            SectionHeader(
                title = "Оперативний журнал подій SCADA",
                trailing = "${alertHistory.size} подій"
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                alertHistory.take(4).forEach { ev ->
                    val evColor = when (ev.level) {
                        "ШТОРМ", "ВІДМОВА" -> StormAlert
                        "RETRY" -> AmberWarning
                        else -> CyanAccent
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, evColor.copy(alpha = 0.35f)),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusChip(text = ev.level, color = evColor)
                            Text(
                                text = ev.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // --- 5. Ручне дискретне опитування SCADA (спадковість ЛР №2) ---
        SectionHeader(title = "Ручний запит контролерів SCADA (ЛР №2)")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Опитування польових давачів вітропарку...",
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    error != null -> {
                        Text(
                            text = "⚠ ПОМИЛКА ТЕЛЕМЕТРІЇ",
                            style = MaterialTheme.typography.labelSmall,
                            color = StormAlert
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "На екрані відображаються останні достовірні показники (SCADA утримує останній зріз).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        InfoRow("Швидкість вітру:", "${fmt1(windSpeed)} м/с")
                        InfoRow("Активна потужність 5-ти ВЕУ:", "${fmt2(power)} МВт")
                        InfoRow("Режим SCADA:", status)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.refreshTelemetry() },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = if (error != null) {
                        ButtonDefaults.buttonColors(
                            containerColor = StormAlert,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(
                        when {
                            isLoading -> "Йде зчитування..."
                            error != null -> "Повторити опитування давачів"
                            else -> "Опитати польові контролери SCADA"
                        }
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Фізичні параметри аеродинамічного контуру:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                InfoRow("Пускова швидкість (Cut-in):", "3.0 м/с")
                InfoRow("Номінальна швидкість:", "12.0 м/с")
                InfoRow("Штормовий захист (Cut-out):", "25.0 м/с")
                InfoRow("Теоретична межа Беца:", "59.3 %")
            }
        }
    }
}

private fun fmt1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun fmt2(value: Double): String = String.format(Locale.US, "%.2f", value)
