package com.arv.proyectofinalabel.ui

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arv.proyectofinalabel.data.AppDatabase
import com.arv.proyectofinalabel.model.PuntoRuta
import com.arv.proyectofinalabel.model.Ruta
import com.arv.proyectofinalabel.model.Waypoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

class RutaViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.rutaDao()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _tiempoTranscurrido = MutableStateFlow(0L)
    val tiempoTranscurrido = _tiempoTranscurrido.asStateFlow()

    private val _distanciaAcumulada = MutableStateFlow(0.0)
    val distanciaAcumulada = _distanciaAcumulada.asStateFlow()

    private val _velocidadActual = MutableStateFlow(0.0)
    val velocidadActual = _velocidadActual.asStateFlow()

    private val _puntosRutaActual = MutableStateFlow<List<PuntoRuta>>(emptyList())
    val puntosRutaActual = _puntosRutaActual.asStateFlow()

    private val _waypointsRutaActual = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypointsRutaActual = _waypointsRutaActual.asStateFlow()

    val rutasGuardadas = dao.obtenerTodasLasRutas()

    private var rutaActualId: Long? = null
    private var jobCronometro: Job? = null
    private var ultimoPunto: PuntoRuta? = null
    private var ultimoTiempoMillis: Long = 0L

    private val _ubicacionInicial = MutableStateFlow<Location?>(null)
    val ubicacionInicial = _ubicacionInicial.asStateFlow()

    private var trackingGlobalIniciado = false
    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun obtenerUbicacionRapida() {
        if (trackingGlobalIniciado) return
        trackingGlobalIniciado = true

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    _ubicacionInicial.value = location

                    if (_isRecording.value) {
                        procesarNuevaUbicacion(location)
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    fun iniciarRuta() {
        viewModelScope.launch {
            val nuevaRuta = Ruta(nombre = "Grabando ruta...")
            rutaActualId = dao.insertarRuta(nuevaRuta)

            _isRecording.value = true
            _tiempoTranscurrido.value = 0L
            _distanciaAcumulada.value = 0.0
            _velocidadActual.value = 0.0
            ultimoPunto = null
            ultimoTiempoMillis = 0L
            _puntosRutaActual.value = emptyList()
            _waypointsRutaActual.value = emptyList()

            iniciarCronometro()
            _ubicacionInicial.value?.let { procesarNuevaUbicacion(it) }
        }
    }

    private fun procesarNuevaUbicacion(location: Location) {
        val id = rutaActualId ?: return
        val precisionAceptable = location.hasAccuracy() && location.accuracy <= 25f

        if (precisionAceptable) {
            val nuevoPunto = PuntoRuta(
                rutaId = id,
                lat = location.latitude,
                lng = location.longitude
            )

            var dist = 0.0
            var meHeMovido = true

            if (ultimoPunto != null) {
                dist = calcularDistanciaHaversine(ultimoPunto!!, nuevoPunto)
                if (dist < 2.5) {
                    meHeMovido = false
                }
            }

            if (meHeMovido) {
                viewModelScope.launch { dao.insertarPuntoRuta(nuevoPunto) }
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
                // Si llevas parado más de 3 segundos, ponemos la velocidad a 0.
                val tiempoSinMoverse = (System.currentTimeMillis() - ultimoTiempoMillis) / 1000.0
                if (tiempoSinMoverse > 3.0) {
                    _velocidadActual.value = 0.0
                }
            }
        }
    }

    fun detenerRuta(nombreFinal: String) {
        val id = rutaActualId ?: return

        _isRecording.value = false
        jobCronometro?.cancel()

        viewModelScope.launch {
            val duracionFinal = _tiempoTranscurrido.value
            val distanciaFinal = _distanciaAcumulada.value
            val velocidadMedia = if (duracionFinal > 0)
                (distanciaFinal / 1000.0) / (duracionFinal / 3600000.0)
            else 0.0

            val rutaActualizada = Ruta(
                id = id,
                nombre = nombreFinal.ifBlank { "Ruta ${System.currentTimeMillis()}" },
                distancia = distanciaFinal,
                duracion = duracionFinal,
                velocidadMedia = velocidadMedia
            )
            dao.actualizarRuta(rutaActualizada)
            rutaActualId = null
        }
    }

    fun agregarWaypoint(nombre: String, desc: String) {
        val id = rutaActualId ?: return
        viewModelScope.launch {
            _ubicacionInicial.value?.let { location ->
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

    private fun iniciarCronometro() {
        jobCronometro = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1000)
                _tiempoTranscurrido.value += 1000
            }
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