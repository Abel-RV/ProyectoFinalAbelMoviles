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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
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

    // --- GESTIÓN DE PERMISOS ---
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
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
                    onStartStop = {
                        if (isRecording) viewModel.detenerRuta(it) else viewModel.iniciarRuta()
                    },
                    onAddWaypoint = { nombre, desc ->
                        viewModel.agregarWaypoint(nombre, desc)
                    }
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
fun BotonesControl(
    isRecording: Boolean,
    onStartStop: (String) -> Unit,
    onAddWaypoint: (String, String) -> Unit
) {
    Column(horizontalAlignment = Alignment.End) {
        // Botón Waypoint (Solo si graba)
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

        // Botón Principal
        var showStopDialog by remember { mutableStateOf(false) }

        FloatingActionButton(
            onClick = {
                if (isRecording) showStopDialog = true else onStartStop("")
            },
            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ) {
            Icon(
                if (isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = "Acción"
            )
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
fun PantallaMapa(viewModel: RutaViewModel) {
    val context = LocalContext.current
    val puntos by viewModel.puntosRutaActual.collectAsState()
    val waypoints by viewModel.waypointsRutaActual.collectAsState()

    // Métricas
    val distancia by viewModel.distanciaAcumulada.collectAsState()
    val tiempo by viewModel.tiempoTranscurrido.collectAsState()
    val velocidad by viewModel.velocidadActual.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // Panel Superior (Métricas)
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

        // Mapa OSMDroid
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)

                    // --- 1. ACTIVAR EL MOVIMIENTO MANUAL ---
                    setMultiTouchControls(true) // Permite hacer zoom con dos dedos
                    // (Hemos quitado el setOnTouchListener que bloqueaba el arrastre)

                    controller.setZoom(18.0)
                }
            },
            update = { mapView ->
                mapView.overlays.clear()

                // DIBUJAR RUTA (POLILÍNEA)
                if (puntos.isNotEmpty()) {
                    val polyline = Polyline()
                    polyline.setPoints(puntos.map { GeoPoint(it.lat, it.lng) })

                    polyline.outlinePaint.color = android.graphics.Color.parseColor("#2196F3")
                    polyline.outlinePaint.strokeWidth = 15f

                    mapView.overlays.add(polyline)

                    // Centrar mapa en la última posición
                    val ultimo = puntos.last()
                    mapView.controller.setCenter(GeoPoint(ultimo.lat, ultimo.lng))
                }

                // DIBUJAR MARKERS (PUNTOS ROJOS)
                waypoints.forEach { wp ->
                    val marker = Marker(mapView)
                    marker.position = GeoPoint(wp.lat, wp.lng)
                    marker.title = wp.nombre
                    marker.snippet = wp.descripcion

                    val iconDrawable = crearIconoDesdeVector(context, Icons.Default.LocationOn, android.graphics.Color.RED)
                    marker.icon = iconDrawable
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                    mapView.overlays.add(marker)
                }

                mapView.invalidate()
            },

            // --- 2. ZONA FIJA EN LA INTERFAZ ---
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp) // Márgenes laterales
                .height(400.dp) // Altura fija del mapa (puedes cambiar este 400 por lo que prefieras)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) // Esquinas redondeadas
        )
    }
}

// --- FUNCIÓN AUXILIAR PARA CREAR ICONOS QUE SI FUNCIONAN ---
fun crearIconoDesdeVector(context: Context, vector: ImageVector, colorTint: Int): Drawable {
    // En lugar de buscar recursos complejos que pueden fallar,
    // dibujamos un marcador redondo (círculo) programáticamente.
    // Esto funciona en cualquier versión de Android sin librerías extra.

    val size = 100 // Tamaño del icono en píxeles
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = colorTint
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }

    // 1. Dibujamos el círculo grande (Color del tinte, ej. Rojo)
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)

    // 2. Dibujamos un punto blanco en el centro para que parezca un marcador
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 6f, paint)

    return BitmapDrawable(context.resources, bitmap)
}

@Composable
fun PantallaHistorial(viewModel: RutaViewModel) {
    val rutas by viewModel.rutasGuardadas.collectAsState(initial = emptyList())

    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(rutas) { ruta ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
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

// --- Componentes Auxiliares ---

@Composable
fun WaypointDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo Punto de Interés") },
        text = {
            Column {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Descripción") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(nombre, desc) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun StopRouteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar Ruta") },
        text = {
            OutlinedTextField(
                value = nombre,
                onValueChange = { nombre = it },
                label = { Text("Nombre de la ruta") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(nombre) }) { Text("Finalizar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
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