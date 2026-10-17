package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AuthSession
import com.lamadriguera.app.domain.model.AuthSessionState
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de autenticación por teléfono + código OTP (Supabase Auth, provider
 * phone). El registro y el login comparten el mismo flujo: enviar código,
 * verificar código.
 */
interface AuthRepository {

    /** Sesión actual, reactiva a cambios de Auth (login, logout, refresh). */
    val sessionState: Flow<AuthSessionState>

    /** Envía un código SMS al teléfono (crea la cuenta si no existe). */
    suspend fun sendLoginCode(phone: String): Result<Unit>

    /** Reenvía el código SMS al teléfono. */
    suspend fun resendLoginCode(phone: String): Result<Unit>

    /** Verifica el código OTP y devuelve la sesión del usuario. */
    suspend fun verifyLoginCode(phone: String, code: String): Result<AuthSession>

    /** Sesión construida para el usuario autenticado actual, o null si no hay. */
    suspend fun currentSession(): Result<AuthSession?>

    suspend fun signOut(): Result<Unit>

    /** Restaura la sesión guardada al arrancar (Splash). */
    suspend fun restoreSession(): Result<AuthSession?>
}
