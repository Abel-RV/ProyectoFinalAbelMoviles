package com.arv.proyectofinalabel

import GestorRutasApp
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Configuración obligatoria de OSMDroid para cargar mapas
        // Carga la configuración (User Agent) desde las preferencias
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        // 2. Establecer el contenido de la UI
        setContent {
            MaterialTheme {
                GestorRutasApp()
            }
        }
    }
}