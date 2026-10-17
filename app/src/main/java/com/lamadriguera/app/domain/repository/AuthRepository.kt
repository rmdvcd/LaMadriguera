package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de autenticación. Implementado en `data/repository` sobre Supabase Auth.
 */
interface AuthRepository {

    /** Usuario actual, reactivo a cambios de sesión (login, logout, refresh). */
    val currentUser: Flow<User?>

    suspend fun signIn(email: String, password: String): Result<User>

    suspend fun signUp(email: String, password: String, displayName: String): Result<User>

    suspend fun signOut(): Result<Unit>

    suspend fun restoreSession(): Result<User?>
}
