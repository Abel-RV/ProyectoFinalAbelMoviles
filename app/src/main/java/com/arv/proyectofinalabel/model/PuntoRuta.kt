package com.arv.proyectofinalabel.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "punto_ruta",
    foreignKeys = [
        ForeignKey(
            entity = Ruta::class,
            parentColumns = ["id"],
            childColumns = ["rutaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rutaId")]
)
data class PuntoRuta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rutaId: Long,
    val lat: Double,
    val lng: Double,
    val timestamp: Long = System.currentTimeMillis()
)
