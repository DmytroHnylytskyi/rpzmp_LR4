package ua.kpi.windpower.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow
import ua.kpi.windpower.data.geo.GeoDistanceCalculator
import ua.kpi.windpower.data.geo.UserGeoPosition
import ua.kpi.windpower.data.geo.WindGeoStation
import ua.kpi.windpower.data.geo.WindPlantStatus
import ua.kpi.windpower.ui.theme.AmberWarning
import ua.kpi.windpower.ui.theme.CyanAccent
import ua.kpi.windpower.ui.theme.EmeraldLight
import ua.kpi.windpower.ui.theme.StormAlert
import ua.kpi.windpower.ui.viewmodels.MapRenderMode
import java.io.File
import java.util.Locale
import kotlin.math.hypot

/**
 * Уніфікований картографічний компонент для відображення ВЕС та GPS-позиції користувача (ПЗ №14 та №16).
 *
 * Підтримує два режими без потреби у зовнішньому API-ключі:
 * 1. [MapRenderMode.OSM] — справжня інтерактивна карта OpenStreetMap на базі `osmdroid` із маркерами
 *    та спливаючим вікном [Marker.showInfoWindow];
 * 2. [MapRenderMode.VECTOR_RADAR] — автономний векторний SCADA-радар України на Compose `Canvas`
 *    із масштабуванням, панорамуванням, координатною сіткою та лінією GPS-відстані.
 */
@Composable
fun WindGeoMapPanel(
    stations: List<WindGeoStation>,
    selectedStationId: Int?,
    userPosition: UserGeoPosition,
    renderMode: MapRenderMode,
    cameraFocusVersion: Int,
    cameraFocusTarget: Pair<Double, Double>?,
    onSelectStation: (Int) -> Unit,
    onOpenDetails: (Int) -> Unit,
    onFocusUser: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedStation = stations.firstOrNull { it.id == selectedStationId }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color(0xFF0A141C))
    ) {
        when (renderMode) {
            MapRenderMode.OSM -> {
                OsmWindMapView(
                    stations = stations,
                    selectedStationId = selectedStationId,
                    userPosition = userPosition,
                    cameraFocusVersion = cameraFocusVersion,
                    cameraFocusTarget = cameraFocusTarget,
                    onSelectStation = onSelectStation,
                    modifier = Modifier.fillMaxSize()
                )
            }
            MapRenderMode.VECTOR_RADAR -> {
                UkraineVectorRadarMap(
                    stations = stations,
                    selectedStationId = selectedStationId,
                    userPosition = userPosition,
                    cameraFocusVersion = cameraFocusVersion,
                    cameraFocusTarget = cameraFocusTarget,
                    onSelectStation = onSelectStation,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Верхня кнопка швидкого центрування карти на GPS-позиції користувача
        OutlinedButton(
            onClick = onFocusUser,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xCC0D1B24),
                contentColor = CyanAccent
            ),
            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.6f)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("📍 Моя позиція", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }

        // Накладене спливаюче InfoWindow для обраної станції з миттєвим переходом у деталі
        selectedStation?.let { station ->
            val distanceKm = GeoDistanceCalculator.distanceKm(
                userPosition.latitude,
                userPosition.longitude,
                station.latitude,
                station.longitude
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xEB0E1A23)),
                border = BorderStroke(1.dp, EmeraldLight.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${station.name} • ${station.status.title}",
                            style = MaterialTheme.typography.labelLarge,
                            color = EmeraldLight
                        )
                        Text(
                            text = "${fmt1(station.currentPowerMw)}/${fmt1(station.capacityMw)} МВт • " +
                                "${fmt1(station.windSpeedMps)} м/с • ${fmt1(distanceKm)} км від вас",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD5E5EE)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    TextButton(
                        onClick = { onOpenDetails(station.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Деталі →", color = CyanAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Інтерактивна карта OpenStreetMap (`osmdroid`) із підтримкою жестів, маркерів ВЕС та `InfoWindow`.
 */
@Composable
private fun OsmWindMapView(
    stations: List<WindGeoStation>,
    selectedStationId: Int?,
    userPosition: UserGeoPosition,
    cameraFocusVersion: Int,
    cameraFocusTarget: Pair<Double, Double>?,
    onSelectStation: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnSelect by rememberUpdatedState(onSelectStation)

    remember(context) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        true
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(6.3)
            controller.setCenter(GeoPoint(48.1, 31.2))
            // Дозволяємо вільне панорамування карти без перехоплення свайпів батьківським списком
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDetach()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Оновлюємо маркери на карті при зміні фільтрів, вибору чи координат GPS
    AndroidView(
        factory = { mapView },
        update = { view ->
            InfoWindow.closeAllInfoWindowsOn(view)
            view.overlays.clear()

            var selectedMarker: Marker? = null

            // 1. Маркери вітрових електростанцій
            stations.forEach { station ->
                val isSelected = station.id == selectedStationId
                val distKm = GeoDistanceCalculator.distanceKm(
                    userPosition.latitude,
                    userPosition.longitude,
                    station.latitude,
                    station.longitude
                )
                val pinColor = when {
                    isSelected -> CyanAccent
                    station.status == WindPlantStatus.MAINTENANCE -> StormAlert
                    station.status == WindPlantStatus.PEAK -> AmberWarning
                    else -> EmeraldLight
                }
                val marker = Marker(view).apply {
                    position = GeoPoint(station.latitude, station.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = createPinDrawable(
                        context = context,
                        fillColor = pinColor.toArgb(),
                        highlighted = isSelected,
                        radiusDp = if (isSelected) 11f else 8.5f
                    )
                    title = station.name
                    snippet = "${station.type.title} (${station.status.title}) • " +
                        "${fmt1(station.currentPowerMw)}/${fmt1(station.capacityMw)} МВт • " +
                        "${fmt1(station.windSpeedMps)} м/с • ${fmt1(distKm)} км"
                    setOnMarkerClickListener { clickedMarker, _ ->
                        clickedMarker.showInfoWindow()
                        currentOnSelect(station.id)
                        true
                    }
                }
                view.overlays.add(marker)
                if (isSelected) {
                    selectedMarker = marker
                }
            }

            // 2. Маркер поточної позиції диспетчера («Ви тут»)
            val userMarker = Marker(view).apply {
                position = GeoPoint(userPosition.latitude, userPosition.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createPinDrawable(
                    context = context,
                    fillColor = Color(0xFF38BDF8).toArgb(),
                    highlighted = true,
                    radiusDp = 9f,
                    ringColor = Color.White.toArgb()
                )
                title = "Ви тут (${userPosition.source.title()})"
                snippet = "Координати: ${fmt4(userPosition.latitude)}, ${fmt4(userPosition.longitude)}"
            }
            view.overlays.add(userMarker)
            view.invalidate()
            selectedMarker?.let { activeMarker ->
                view.post {
                    if (view.isAttachedToWindow) {
                        activeMarker.showInfoWindow()
                    }
                }
            }
        },
        modifier = modifier
    )

    // Автоматичне фокусування камери карти при виборі об'єкта або натисканні «Моя позиція»
    LaunchedEffect(cameraFocusVersion) {
        val target = cameraFocusTarget ?: return@LaunchedEffect
        val targetZoom = if (mapView.zoomLevelDouble < 7.8) 8.4 else mapView.zoomLevelDouble
        mapView.controller.animateTo(
            GeoPoint(target.first, target.second),
            targetZoom,
            550L
        )
    }
}

/**
 * Автономний векторний SCADA-радар України на Jetpack Compose `Canvas` із проєкцією WGS-84,
 * масштабуванням, панорамуванням та двостороннім вибором маркерів (працює й без Інтернету).
 */
@Composable
private fun UkraineVectorRadarMap(
    stations: List<WindGeoStation>,
    selectedStationId: Int?,
    userPosition: UserGeoPosition,
    cameraFocusVersion: Int,
    cameraFocusTarget: Pair<Double, Double>?,
    onSelectStation: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Географічні межі вікна радару (територія України та Азово-Чорноморський коридор)
    val minLon = 21.8
    val maxLon = 40.5
    val minLat = 44.2
    val maxLat = 52.5

    LaunchedEffect(cameraFocusVersion) {
        val target = cameraFocusTarget ?: return@LaunchedEffect
        val targetZoom = maxOf(zoom, 1.85f)
        zoom = targetZoom
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            val w = canvasSize.width.toFloat()
            val h = canvasSize.height.toFloat()
            val basePt = projectPoint(
                lat = target.first,
                lon = target.second,
                width = w,
                height = h,
                minLat = minLat,
                maxLat = maxLat,
                minLon = minLon,
                maxLon = maxLon,
                zoom = 1f,
                pan = Offset.Zero
            )
            val cx = w / 2f
            val cy = h / 2f
            // Зміщуємо ціль трохи вище центру, щоб маркер не перекривався нижнім InfoWindow
            val desiredY = h * 0.42f
            panOffset = Offset(
                x = (-(basePt.x - cx) * targetZoom).coerceIn(-w * 1.2f, w * 1.2f),
                y = (desiredY - cy - (basePt.y - cy) * targetZoom).coerceIn(-h * 1.2f, h * 1.2f)
            )
        } else {
            panOffset = Offset.Zero
        }
    }

    // Спрощений географічний контур материкової України й узбережжя для орієнтації на радарі
    val ukraineContour = remember {
        listOf(
            51.5 to 23.6, 51.9 to 25.5, 51.7 to 29.0, 52.1 to 32.8,
            51.5 to 34.4, 50.2 to 36.2, 49.6 to 38.6, 48.2 to 39.8,
            47.1 to 38.2, 46.7 to 36.8, 46.5 to 35.1, 46.0 to 33.6,
            45.4 to 36.4, 44.4 to 34.1, 45.3 to 32.5, 46.1 to 33.4,
            46.4 to 31.6, 46.4 to 30.7, 45.3 to 29.6, 45.4 to 28.2,
            46.5 to 29.9, 48.0 to 28.4, 48.4 to 26.6, 47.9 to 24.9,
            48.5 to 22.2, 50.1 to 23.1, 51.5 to 23.6
        )
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                        zoom = nextZoom
                        val maxPanX = (size.width * nextZoom * 0.55f).coerceAtLeast(350f)
                        val maxPanY = (size.height * nextZoom * 0.55f).coerceAtLeast(280f)
                        panOffset = Offset(
                            x = (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                            y = (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                        )
                    }
                }
                .pointerInput(stations, zoom, panOffset) {
                    detectTapGestures { tapOffset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val hitRadiusPx = 28.dp.toPx()
                        val closest = stations.minByOrNull { st ->
                            val pt = projectPoint(st.latitude, st.longitude, w, h, minLat, maxLat, minLon, maxLon, zoom, panOffset)
                            hypot(pt.x - tapOffset.x, pt.y - tapOffset.y)
                        }
                        if (closest != null) {
                            val pt = projectPoint(closest.latitude, closest.longitude, w, h, minLat, maxLat, minLon, maxLon, zoom, panOffset)
                            if (hypot(pt.x - tapOffset.x, pt.y - tapOffset.y) <= hitRadiusPx) {
                                onSelectStation(closest.id)
                            }
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // Фоновий радарний градієнт і координатна сітка широти/довготи
            val gridColor = Color(0xFF163242)
            for (latStep in 45..52 step 2) {
                val p1 = projectPoint(latStep.toDouble(), minLon, w, h, minLat, maxLat, minLon, maxLon, zoom, panOffset)
                val p2 = projectPoint(latStep.toDouble(), maxLon, w, h, minLat, maxLat, minLon, maxLon, zoom, panOffset)
                drawLine(gridColor, p1, p2, strokeWidth = 1f)
            }
            for (lonStep in 24..38 step 3) {
                val p1 = projectPoint(minLat, lonStep.toDouble(), w, h, minLat, maxLat, minLon, maxLon, zoom, panOffset)
                val p2 = projectPoint(maxLat, lonStep.toDouble(), w, h, minLat, maxLat, minLon, maxLon, zoom, panOffset)
                drawLine(gridColor, p1, p2, strokeWidth = 1f)
            }

            // Контур території України
            val contourPath = Path()
            ukraineContour.forEachIndexed { idx, (lat, lon) ->
                val pt = projectPoint(lat, lon, w, h, minLat, maxLat, minLon, maxLon, zoom, panOffset)
                if (idx == 0) contourPath.moveTo(pt.x, pt.y) else contourPath.lineTo(pt.x, pt.y)
            }
            drawPath(
                path = contourPath,
                color = EmeraldLight.copy(alpha = 0.08f)
            )
            drawPath(
                path = contourPath,
                color = CyanAccent.copy(alpha = 0.45f),
                style = Stroke(width = 2.2f)
            )

            // Вектор GPS-відстані від позиції користувача до обраної ВЕС
            val userPt = projectPoint(
                userPosition.latitude,
                userPosition.longitude,
                w,
                h,
                minLat,
                maxLat,
                minLon,
                maxLon,
                zoom,
                panOffset
            )
            stations.firstOrNull { it.id == selectedStationId }?.let { selected ->
                val targetPt = projectPoint(
                    selected.latitude,
                    selected.longitude,
                    w,
                    h,
                    minLat,
                    maxLat,
                    minLon,
                    maxLon,
                    zoom,
                    panOffset
                )
                drawLine(
                    color = CyanAccent.copy(alpha = 0.7f),
                    start = userPt,
                    end = targetPt,
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            }

            val selectedPinRadius = 10.dp.toPx()
            val normalPinRadius = 7.dp.toPx()
            val haloRadius = 19.dp.toPx()
            val strokePx = 2.dp.toPx()

            // Маркери ВЕС на радарі
            stations.forEach { station ->
                val isSelected = station.id == selectedStationId
                val pt = projectPoint(
                    station.latitude,
                    station.longitude,
                    w,
                    h,
                    minLat,
                    maxLat,
                    minLon,
                    maxLon,
                    zoom,
                    panOffset
                )
                val color = when {
                    isSelected -> CyanAccent
                    station.status == WindPlantStatus.MAINTENANCE -> StormAlert
                    station.status == WindPlantStatus.PEAK -> AmberWarning
                    else -> EmeraldLight
                }
                if (isSelected) {
                    drawCircle(
                        color = CyanAccent.copy(alpha = 0.25f),
                        radius = haloRadius,
                        center = pt
                    )
                }
                drawCircle(
                    color = color,
                    radius = if (isSelected) selectedPinRadius else normalPinRadius,
                    center = pt
                )
                drawCircle(
                    color = Color.White,
                    radius = if (isSelected) selectedPinRadius else normalPinRadius,
                    center = pt,
                    style = Stroke(width = strokePx)
                )
            }

            // Позиція диспетчера («Ви тут»)
            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.24f),
                radius = haloRadius,
                center = userPt
            )
            drawCircle(
                color = Color(0xFF38BDF8),
                radius = normalPinRadius + 1.dp.toPx(),
                center = userPt
            )
            drawCircle(
                color = Color.White,
                radius = normalPinRadius + 1.dp.toPx(),
                center = userPt,
                style = Stroke(width = strokePx)
            )
        }

        // Кнопка скидання масштабу радару та підказка координат GPS
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GPS: ${fmt2(userPosition.latitude)}°N, ${fmt2(userPosition.longitude)}°E • x${fmt1(zoom.toDouble())}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFAAD8E6),
                modifier = Modifier
                    .background(Color(0xB80A141C), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            if (zoom > 1.05f || panOffset != Offset.Zero) {
                OutlinedButton(
                    onClick = {
                        zoom = 1f
                        panOffset = Offset.Zero
                    },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Скинути 1x", fontSize = 10.sp)
                }
            }
        }
    }
}

private fun projectPoint(
    lat: Double,
    lon: Double,
    width: Float,
    height: Float,
    minLat: Double,
    maxLat: Double,
    minLon: Double,
    maxLon: Double,
    zoom: Float,
    pan: Offset
): Offset {
    val normX = ((lon - minLon) / (maxLon - minLon)).toFloat()
    val normY = (1.0 - (lat - minLat) / (maxLat - minLat)).toFloat()
    val baseX = width * (0.06f + normX * 0.88f)
    val baseY = height * (0.06f + normY * 0.64f)
    val cx = width / 2f
    val cy = height / 2f
    return Offset(
        x = cx + (baseX - cx) * zoom + pan.x,
        y = cy + (baseY - cy) * zoom + pan.y
    )
}

private fun createPinDrawable(
    context: android.content.Context,
    fillColor: Int,
    highlighted: Boolean,
    radiusDp: Float,
    ringColor: Int = android.graphics.Color.WHITE
): Drawable {
    val density = context.resources.displayMetrics.density
    val radiusPx = radiusDp * density
    val haloPx = if (highlighted) 4f * density else 1.5f * density
    val sizePx = ((radiusPx + haloPx) * 2f).toInt().coerceAtLeast(16)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val center = sizePx / 2f

    if (highlighted) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (fillColor and 0x00FFFFFF) or 0x55000000
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radiusPx + haloPx, haloPaint)
    }

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radiusPx, fillPaint)

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    canvas.drawCircle(center, center, radiusPx - density, strokePaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun fmt1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun fmt2(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun fmt4(value: Double): String = String.format(Locale.US, "%.4f", value)
