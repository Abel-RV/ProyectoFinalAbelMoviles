package com.arv.proyectofinalabel.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.arv.proyectofinalabel.model.PuntoRuta
import com.arv.proyectofinalabel.model.Ruta
import com.arv.proyectofinalabel.model.Waypoint
import kotlinx.coroutines.flow.Flow

@Dao
interface RutaDao{
    @Insert
    suspend fun insertarRuta(ruta: Ruta): Long

    @Update
    suspend fun actualizarRuta(ruta: Ruta)

    @Query("SELECT * FROM ruta ORDER BY id DESC")
    fun obtenerTodasLasRutas(): Flow<List<Ruta>>

    @Insert
    suspend fun insertarPuntoRuta(punto: PuntoRuta)

    @Query("SELECT * FROM punto_ruta WHERE rutaId = :rutaId ORDER BY timestamp ASC")
    fun obtenerPuntosDeRuta(rutaId: Long): Flow<List<PuntoRuta>>

    @Insert
    suspend fun insertarWaypoint(waypoint: Waypoint)

    @Query("SELECT * FROM waypoint WHERE rutaId = :rutaId")
    fun obtenerWaypointsDeRuta(rutaId: Long): Flow<List<Waypoint>>
}