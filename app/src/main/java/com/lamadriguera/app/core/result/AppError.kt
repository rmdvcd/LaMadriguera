package com.lamadriguera.app.core.result

/**
 * Errores tipados de dominio.
 * Útil para mapear errores de Supabase/Ktor a mensajes en español.
 */
sealed class AppError : Exception() {
    data class Network(override val message: String = "Sin conexión a internet") : AppError()
    data class Unauthorized(override val message: String = "Sesión expirada. Inicia sesión de nuevo.") : AppError()
    data class NotFound(override val message: String = "Recurso no encontrado") : AppError()
    data class Validation(override val message: String) : AppError()
    data class Server(override val message: String = "Error del servidor. Intenta más tarde.") : AppError()
    data class Unknown(override val message: String = "Ha ocurrido un error inesperado") : AppError()
    data class Conflict(override val message: String = "Conflicto de datos") : AppError()
}
