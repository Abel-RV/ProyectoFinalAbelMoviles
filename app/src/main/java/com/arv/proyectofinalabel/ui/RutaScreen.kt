import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.proyectofinalabel.ui.RutaViewModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestorRutasApp() {
    val viewModel: RutaViewModel = viewModel()
    val isRecording by viewModel.isRecording.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.obtenerUbicacionRapida()
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            viewModel.obtenerUbicacionRapida()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showHistory) "Historial de Rutas" else "Mapa de Rutas") },
                actions = {
                    IconButton(onClick = { showHistory = !showHistory }) {
                        Icon(
                            if (showHistory) Icons.Default.Map else Icons.Default.History,
                            contentDescription = "Cambiar vista"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            if (!showHistory) {
                BotonesControl(
                    isRecording = isRecording,
                    onStartStop = { if (isRecording) viewModel.detenerRuta(it) else viewModel.iniciarRuta() },
                    onAddWaypoint = { nombre, desc -> viewModel.agregarWaypoint(nombre, desc) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (showHistory) {
                PantallaHistorial(viewModel)
            } else {
                PantallaMapa(viewModel)
            }
        }
    }
}

@Composable
fun PantallaMapa(viewModel: RutaViewModel) {
    val context = LocalContext.current
    val puntos by viewModel.puntosRutaActual.collectAsState()
    val waypoints by viewModel.waypointsRutaActual.collectAsState()
    val ubicacionInicial by viewModel.ubicacionInicial.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()

    val distancia by viewModel.distanciaAcumulada.collectAsState()
    val tiempo by viewModel.tiempoTranscurrido.collectAsState()
    val velocidad by viewModel.velocidadActual.collectAsState()

    var centrarMapaTrigger by remember { mutableStateOf(1L) }
    var ultimoCentrado by remember { mutableStateOf(0L) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            InfoItem("Distancia", "${String.format("%.0f", distancia)} m")
            InfoItem("Tiempo", formatearTiempo(tiempo))
            InfoItem("Velocidad", "${String.format("%.1f", velocidad)} km/h")
        }

        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(450.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(19.0)
                    }
                },
                update = { mapView ->
                    var polyline = mapView.overlays.find { it is Polyline } as? Polyline
                    if (polyline == null) {
                        polyline = Polyline()
                        polyline.outlinePaint.color = android.graphics.Color.parseColor("#2196F3")
                        polyline.outlinePaint.strokeWidth = 15f
                        mapView.overlays.add(polyline)
                    }
                    if (puntos.isNotEmpty()) {
                        polyline.setPoints(puntos.map { GeoPoint(it.lat, it.lng) })
                    }

                    if (ubicacionInicial != null) {
                        val geoActual = GeoPoint(ubicacionInicial!!.latitude, ubicacionInicial!!.longitude)

                        var markerActual = mapView.overlays.find { it is Marker && it.title == "Tu posición actual" } as? Marker
                        if (markerActual == null) {
                            markerActual = Marker(mapView)
                            markerActual.title = "Tu posición actual"
                            markerActual.icon = crearIconoPuntoAzul(context)
                            markerActual.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            mapView.overlays.add(markerActual)
                        }

                        markerActual.position = geoActual

                        // Centrar cámara con ANIMACIÓN suave en lugar de salto brusco
                        if (isRecording && puntos.isNotEmpty()) {
                            mapView.controller.animateTo(geoActual)
                        } else if (centrarMapaTrigger != ultimoCentrado) {
                            mapView.controller.animateTo(geoActual)
                            ultimoCentrado = centrarMapaTrigger
                        }
                    }

                    mapView.overlays.removeAll { it is Marker && (it as Marker).title != "Tu posición actual" }
                    waypoints.forEach { wp ->
                        val marker = Marker(mapView)
                        marker.position = GeoPoint(wp.lat, wp.lng)
                        marker.title = wp.nombre
                        marker.snippet = wp.descripcion
                        marker.icon = crearIconoWaypointRojo(context)
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        mapView.overlays.add(marker)
                    }

                    mapView.invalidate()
                }
            )

            SmallFloatingActionButton(
                onClick = { centrarMapaTrigger = System.currentTimeMillis() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Centrar mapa")
            }
        }
    }
}

// Icono Azul que resalta tu posición actual (Punto Azul típico de mapas)
fun crearIconoPuntoAzul(context: Context): Drawable {
    val size = 70
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = android.graphics.Paint().apply { isAntiAlias = true }

    paint.color = android.graphics.Color.parseColor("#442196F3")
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    paint.color = android.graphics.Color.parseColor("#2196F3")
    canvas.drawCircle(size / 2f, size / 2f, size / 3.5f, paint)

    paint.color = android.graphics.Color.WHITE
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 4f
    canvas.drawCircle(size / 2f, size / 2f, size / 3.5f, paint)

    return BitmapDrawable(context.resources, bitmap)
}

// Icono Rojo genérico para los Waypoints
fun crearIconoWaypointRojo(context: Context): Drawable {
    val size = 90
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.RED
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 6f, paint)
    return BitmapDrawable(context.resources, bitmap)
}

@Composable
fun BotonesControl(isRecording: Boolean, onStartStop: (String) -> Unit, onAddWaypoint: (String, String) -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        if (isRecording) {
            var showWaypointDialog by remember { mutableStateOf(false) }
            FloatingActionButton(
                onClick = { showWaypointDialog = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Añadir Waypoint")
            }
            if (showWaypointDialog) {
                WaypointDialog(
                    onDismiss = { showWaypointDialog = false },
                    onConfirm = { nombre, desc ->
                        onAddWaypoint(nombre, desc)
                        showWaypointDialog = false
                    }
                )
            }
        }
        var showStopDialog by remember { mutableStateOf(false) }
        FloatingActionButton(
            onClick = { if (isRecording) showStopDialog = true else onStartStop("") },
            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ) {
            Icon(if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = "Acción")
        }
        if (showStopDialog) {
            StopRouteDialog(
                onDismiss = { showStopDialog = false },
                onConfirm = { nombre ->
                    onStartStop(nombre)
                    showStopDialog = false
                }
            )
        }
    }
}

@Composable
fun PantallaHistorial(viewModel: RutaViewModel) {
    val rutas by viewModel.rutasGuardadas.collectAsState(initial = emptyList())
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(rutas) { ruta ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(ruta.nombre, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Distancia: ${String.format("%.2f", ruta.distancia)} m")
                    Text("Duración: ${formatearTiempo(ruta.duracion)}")
                    Text("Vel. Media: ${String.format("%.1f", ruta.velocidadMedia)} km/h")
                }
            }
        }
    }
}

@Composable
fun WaypointDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo Punto de Interés") },
        text = {
            Column {
                OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción") })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(nombre, desc) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun StopRouteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar Ruta") },
        text = {
            OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre de la ruta") })
        },
        confirmButton = { Button(onClick = { onConfirm(nombre) }) { Text("Finalizar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

fun formatearTiempo(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes)
    return String.format("%02d:%02d", minutes, seconds)
}