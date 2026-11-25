package ua.kpi.windpower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.kpi.windpower.di.AppContainer
import ua.kpi.windpower.navigation.Screen
import ua.kpi.windpower.ui.screens.AuthProfileScreen
import ua.kpi.windpower.ui.screens.CalculatorScreen
import ua.kpi.windpower.ui.screens.DashboardScreen
import ua.kpi.windpower.ui.screens.DatabaseHistoryScreen
import ua.kpi.windpower.ui.screens.StationDetailScreen
import ua.kpi.windpower.ui.screens.StationRegistrationScreen
import ua.kpi.windpower.ui.screens.StationsMapScreen
import ua.kpi.windpower.ui.screens.TelemetryMvvmScreen
import ua.kpi.windpower.ui.screens.TurbineFleetScreen
import ua.kpi.windpower.ui.screens.WindDetailsScreen
import ua.kpi.windpower.ui.screens.WindForecastScreen
import ua.kpi.windpower.ui.theme.WindPowerPlantTheme
import ua.kpi.windpower.ui.viewmodels.WindStationsMapViewModel

/**
 * Головна точка входу мобільного застосунку «AeroGrid 4.0 • ВЕС Моніторинг».
 *
 * Повноцінна інженерна реалізація системи управління вітропарком (ПЗ №1–16):
 * - Jetpack Navigation Component із 11 типізованими маршрутами та аргументами;
 * - Потокова SCADA-телеметрія на Kotlin Coroutines і Flow (map, filter, debounce, combine, zip, retryWhen, catch, SharedFlow);
 * - Геопросторовий моніторинг ВЕС (LazyColumn + багаторівнева фільтрація/сортування + OpenStreetMap/Vector GPS-карта);
 * - Repository + DataSource, REST API (Retrofit2 + Gson), JWT-автентифікація (DataStore + AuthInterceptor + RBAC) і Room SQLite.
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WindPowerPlantTheme {
                val context = LocalContext.current
                val activeSession by AppContainer.get(context).tokenStorage.sessionFlow
                    .collectAsState(initial = null)
                val activeDispatcherName = activeSession?.fullName
                    ?.substringBefore(" (")
                    ?.replace("/", "-")
                    ?.trim()
                    ?.ifBlank { "Головний_Диспетчер" }
                    ?: "Головний_Диспетчер"

                val navController = rememberNavController()
                val stationsMapViewModel: WindStationsMapViewModel = viewModel()

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val bottomNavItems = listOf(
                    BottomNavigationItem(
                        route = Screen.Dashboard.route,
                        title = "Моніторинг",
                        icon = "⚡"
                    ),
                    BottomNavigationItem(
                        route = Screen.ScadaMvvm.route,
                        title = "Потоки",
                        icon = "🌀"
                    ),
                    BottomNavigationItem(
                        route = Screen.StationsMap.route,
                        title = "Карта ВЕС",
                        icon = "🗺"
                    ),
                    BottomNavigationItem(
                        route = Screen.WindForecast.createRoute(activeDispatcherName),
                        title = "Прогноз",
                        icon = "📊"
                    ),
                    BottomNavigationItem(
                        route = Screen.AuthProfile.route,
                        title = "Диспетчер",
                        icon = "👤"
                    )
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "AeroGrid 4.0 • ВЕС Моніторинг",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            },
                            actions = {
                                val isDbScreen = currentRoute == Screen.DatabaseHistory.route ||
                                    currentRoute == Screen.StationRegistration.route
                                TextButton(
                                    onClick = {
                                        navController.navigate(Screen.DatabaseHistory.route) {
                                            launchSingleTop = true
                                        }
                                    }
                                ) {
                                    Text(
                                        text = "🗄 БД",
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isDbScreen) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            bottomNavItems.forEach { item ->
                                val isSelected = currentRoute == item.route ||
                                    (item.route.startsWith("wind_forecast") && currentRoute?.startsWith("wind_forecast") == true) ||
                                    (item.route == Screen.StationsMap.route && currentRoute?.startsWith("station_detail") == true)

                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Text(
                                            text = item.icon,
                                            fontSize = if (isSelected) 20.sp else 16.sp
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.title,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Dashboard.route
                        ) {
                            // 1. Оперативний моніторинг (Canvas-анімація турбіни)
                            composable(Screen.Dashboard.route) {
                                DashboardScreen(
                                    onNavigateToFleet = { navController.navigate(Screen.Fleet.route) },
                                    onNavigateToCalculator = { navController.navigate(Screen.Calculator.route) },
                                    onNavigateToMap = { navController.navigate(Screen.StationsMap.route) },
                                    onNavigateToDatabase = { navController.navigate(Screen.DatabaseHistory.route) }
                                )
                            }

                            // 2. SCADA Потоки Coroutines & Flow + MVVM StateFlow (ПЗ №13)
                            composable(Screen.ScadaMvvm.route) {
                                TelemetryMvvmScreen()
                            }

                            // 3. Геопросторовий реєстр ВЕС: LazyColumn, фільтрація/сортування, OSM-карта та GPS (ПЗ №14–16)
                            composable(Screen.StationsMap.route) {
                                StationsMapScreen(
                                    navController = navController,
                                    viewModel = stationsMapViewModel
                                )
                            }

                            // 4. Детальна інформація про обрану станцію ВЕС (ПЗ №14)
                            composable(
                                route = Screen.StationDetail.route,
                                arguments = listOf(
                                    navArgument("stationId") { type = NavType.IntType }
                                )
                            ) { backStackEntry ->
                                val stationId = backStackEntry.arguments?.getInt("stationId") ?: 1
                                StationDetailScreen(
                                    navController = navController,
                                    stationId = stationId,
                                    viewModel = stationsMapViewModel
                                )
                            }

                            // 5. Прогноз виробітку вітропарку та REST API (ПЗ №9, №10, №12)
                            composable(
                                route = "wind_forecast/{userName}",
                                arguments = listOf(
                                    navArgument("userName") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val userName = backStackEntry.arguments?.getString("userName") ?: "Диспетчер"
                                WindForecastScreen(navController, userName)
                            }

                            // 6. Інженерні деталі з аргументом кількості турбін
                            composable(
                                route = "wind_details/{turbinesCount}",
                                arguments = listOf(
                                    navArgument("turbinesCount") { type = NavType.IntType }
                                )
                            ) { backStackEntry ->
                                val count = backStackEntry.arguments?.getInt("turbinesCount") ?: 6
                                WindDetailsScreen(navController, count)
                            }

                            // 7. Профіль, JWT-автентифікація та RBAC (ПЗ №11)
                            composable(Screen.AuthProfile.route) {
                                AuthProfileScreen()
                            }

                            // 8. Локальна реляційна база даних Room та аналітика телеметрії
                            composable(Screen.DatabaseHistory.route) {
                                DatabaseHistoryScreen(
                                    onNavigateToRegisterStation = { navController.navigate(Screen.StationRegistration.route) }
                                )
                            }

                            // 9. Реєстрація нового об'єкта ВЕС у системі
                            composable(Screen.StationRegistration.route) {
                                StationRegistrationScreen(onNavigateBack = { navController.popBackStack() })
                            }

                            // 10. Парк турбін ВЕС-01..ВЕС-05
                            composable(Screen.Fleet.route) {
                                TurbineFleetScreen(onNavigateBack = { navController.popBackStack() })
                            }

                            // 11. Аеродинамічний калькулятор Беца
                            composable(Screen.Calculator.route) {
                                CalculatorScreen(onNavigateBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Елемент конфігурації пункту нижнього навігаційного меню.
 */
private data class BottomNavigationItem(
    val route: String,
    val title: String,
    val icon: String
)
