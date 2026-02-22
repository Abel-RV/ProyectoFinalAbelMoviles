package com.arv.proyectofinalabel.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ruta")
data class Ruta(
    @PrimaryKey val id: Long = System.currentTimeMillis(),
    val nombre: String,
    val distancia: Double = 0.0,
    val duracion: Long = 0L,
    val velocidadMedia: Double = 0.0
)