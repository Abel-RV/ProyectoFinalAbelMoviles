package com.arv.proyectofinalabel.ui

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arv.proyectofinalabel.data.AppDatabase
import com.arv.proyectofinalabel.model.PuntoRuta
import com.arv.proyectofinalabel.model.Ruta
import com.arv.proyectofinalabel.model.Waypoint
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.*

class RutaViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.rutaDao()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    // --- ESTADOS DE LA UI ---
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _tiempoTranscurrido = MutableStateFlow(0L) // Milisegundos
    val tiempoTranscurrido = _tiempoTranscurrido.asStateFlow()

    private val _distanciaAcumulada = MutableStateFlow(0.0) // Metros
    val distanciaAcumulada = _distanciaAcumulada.asStateFlow()

    private val _velocidadActual = MutableStateFlow(0.0) // km/h
    val velocidadActual = _velocidadActual.asStateFlow()

    // Datos para dibujar en el mapa en tiempo real
    private val _puntosRutaActual = MutableStateFlow<List<PuntoRuta>>(emptyList())
    val puntosRutaActual = _puntosRutaActual.asStateFlow()

    private val _waypointsRutaActual = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypointsRutaActual = _waypointsRutaActual.asStateFlow()

    // Historial
    val rutasGuardadas = dao.obtenerTodasLasRutas()

    // Variables internas
    private var rutaActualId: Long? = null
    private var jobCronometro: Job? = null
    private var jobLocation: Job? = null
    private var ultimoPunto: PuntoRuta? = null
    private var ultimoTiempoMillis: Long = 0L // NUEVA VARIABLE PARA CÁLCULO DE VELOCIDAD

    // --- FUNCIONES PÚBLICAS (Llamadas desde la UI) ---

    fun iniciarRuta() {
        viewModelScope.launch {
            // Creamos la ruta en BD (vacía inicialmente)
            val nuevaRuta = Ruta(nombre = "Grabando ruta...")
            rutaActualId = dao.insertarRuta(nuevaRuta)

            // Reiniciamos estados
            _isRecording.value = true
            _tiempoTranscurrido.value = 0L
            _distanciaAcumulada.value = 0.0
            _velocidadActual.value = 0.0
            ultimoPunto = null
            ultimoTiempoMillis = 0L // REINICIAMOS TIEMPO
            _puntosRutaActual.value = emptyList()
            _waypointsRutaActual.value = emptyList()

            // Arrancamos procesos
            iniciarCronometro()
            iniciarTrackingGPS()
        }
    }

    fun detenerRuta(nombreFinal: String) {
        val id = rutaActualId ?: return

        // Detener procesos
        _isRecording.value = false
        jobCronometro?.cancel()
        jobLocation?.cancel()

        viewModelScope.launch {
            // Cálculos finales
            val duracionFinal = _tiempoTranscurrido.value
            val distanciaFinal = _distanciaAcumulada.value
            val velocidadMedia = if (duracionFinal > 0)
                (distanciaFinal / 1000.0) / (duracionFinal / 3600000.0) // km/h
            else 0.0

            // Actualizamos la ruta en la BD con los datos finales
            val rutaActualizada = Ruta(
                id = id,
                nombre = nombreFinal.ifBlank { "Ruta ${System.currentTimeMillis()}" },
                distancia = distanciaFinal,
                duracion = duracionFinal,
                velocidadMedia = velocidadMedia
            )
            dao.actualizarRuta(rutaActualizada)

            // Limpieza
            rutaActualId = null
        }
    }

    fun agregarWaypoint(nombre: String, desc: String) {
        val id = rutaActualId ?: return
        viewModelScope.launch {
            obtenerUbicacionActualSingle()?.let { location ->
                val wp = Waypoint(
                    rutaId = id,
                    lat = location.latitude,
                    lng = location.longitude,
                    nombre = nombre,
                    descripcion = desc,
                    fotoPath = null
                )
                dao.insertarWaypoint(wp)
                _waypointsRutaActual.value += wp
            }
        }
    }

    // --- PROCESOS INTERNOS ---

    private fun iniciarCronometro() {
        jobCronometro = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1000)
                _tiempoTranscurrido.value += 1000
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun iniciarTrackingGPS() {
        jobLocation = viewModelScope.launch {
            while (_isRecording.value) {
                val location = obtenerUbicacionActualSingle()

                if (location != null && rutaActualId != null) {
                    val nuevoPunto = PuntoRuta(
                        rutaId = rutaActualId!!,
                        lat = location.latitude,
                        lng = location.longitude
                    )

                    var dist = 0.0
                    var meHeMovido = true // Variable para saber si el movimiento es real

                    // Si hay un punto anterior, calculamos la distancia antes de hacer nada
                    if (ultimoPunto != null) {
                        dist = calcularDistanciaHaversine(ultimoPunto!!, nuevoPunto)

                        // FILTRO ANTI-RUIDO: Si la distancia es menor a 3 metros, lo ignoramos
                        if (dist < 3.0) {
                            meHeMovido = false
                        }
                    }

                    // Solo guardamos y sumamos si de verdad nos hemos movido
                    if (meHeMovido) {
                        dao.insertarPuntoRuta(nuevoPunto)
                        _puntosRutaActual.value += nuevoPunto

                        val tiempoActualMillis = System.currentTimeMillis()

                        if (ultimoPunto != null && ultimoTiempoMillis > 0) {
                            _distanciaAcumulada.value += dist

                            val segundosTranscurridos = (tiempoActualMillis - ultimoTiempoMillis) / 1000.0

                            if (segundosTranscurridos > 0) {
                                val velocidadKmh = (dist / segundosTranscurridos) * 3.6
                                _velocidadActual.value = velocidadKmh
                            }
                        }
                        ultimoPunto = nuevoPunto
                        ultimoTiempoMillis = tiempoActualMillis

                    } else {
                        // Si meHeMovido es false (estamos quietos), forzamos la velocidad a 0
                        _velocidadActual.value = 0.0
                    }
                }

                delay(10_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun obtenerUbicacionActualSingle(): Location? {
        return try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        } catch (e: Exception) {
            null
        }
    }

    private fun calcularDistanciaHaversine(p1: PuntoRuta, p2: PuntoRuta): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(p1.lat)) * cos(Math.toRadians(p2.lat)) *
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}