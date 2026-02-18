package com.arv.proyectofinalabel.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.arv.proyectofinalabel.model.PuntoRuta
import com.arv.proyectofinalabel.model.Ruta
import com.arv.proyectofinalabel.model.Waypoint

@Database(entities = [Ruta::class, PuntoRuta::class, Waypoint::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rutaDao(): RutaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gestor_rutas_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}