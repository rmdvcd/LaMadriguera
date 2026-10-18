package com.lamadriguera.app.feature.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Última ubicación conocida para compartir en el chat (se cifra dentro del
 * payload como los demás mensajes; no se sube a ningún servicio de mapas).
 */
object LocationHelper {

    const val PERMISSION = android.Manifest.permission.ACCESS_COARSE_LOCATION

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /** Mejor última posición conocida (gps o red), o null si el sistema no tiene ninguna. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    }
}
