package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AuthSession
import com.lamadriguera.app.domain.model.AuthSessionState
import com.lamadriguera.app.domain.model.Profile
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.DeviceAccountRegistry
import com.lamadriguera.app.domain.repository.ProfilesRepository
import com.lamadriguera.app.domain.repository.UsersRepository
import com.lamadriguera.app.domain.usecase.OtpCode
import com.lamadriguera.app.domain.usecase.PhoneNumber
import com.lamadriguera.app.domain.usecase.ProfileDefaults
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Autenticación por teléfono + código OTP sobre Supabase Auth (provider phone).
 *
 * Registro y login comparten flujo: `signInWith(OTP)` envía el SMS (crea la
 * cuenta si no existe) y `verifyPhoneOtp` confirma el código. La fila de
 * `users`/`profiles` se provisiona aquí: el trigger de Auth crea `users` y este
 * repositorio asegura el perfil inicial.
 */
class SupabaseAuthRepository @Inject constructor(
    private val client: SupabaseClient,
    private val users: UsersRepository,
    private val profiles: ProfilesRepository,
    private val deviceAccounts: DeviceAccountRegistry
) : AuthRepository {

    override val sessionState: Flow<AuthSessionState> = client.auth.sessionStatus.map { status ->
        when (status) {
            SessionStatus.Initializing -> AuthSessionState.Initializing
            is SessionStatus.NotAuthenticated -> AuthSessionState.Unauthenticated
            is SessionStatus.RefreshFailure -> AuthSessionState.Unauthenticated
            is SessionStatus.Authenticated -> when (val session = buildSession()) {
                is Result.Success -> AuthSessionState.Authenticated(session.data)
                is Result.Error -> AuthSessionState.Error(
                    session.message ?: "No se pudo cargar tu cuenta"
                )
            }
        }
    }

    override suspend fun sendLoginCode(phone: String): Result<Unit> {
        val normalized = PhoneNumber.normalize(phone)
            ?: return Result.Error(AppError.Validation(PhoneNumber.INVALID_MESSAGE))
        return request {
            client.auth.signInWith(OTP) {
                this.phone = normalized
                createUser = true
            }
        }
    }

    override suspend fun resendLoginCode(phone: String): Result<Unit> {
        val normalized = PhoneNumber.normalize(phone)
            ?: return Result.Error(AppError.Validation(PhoneNumber.INVALID_MESSAGE))
        return request {
            client.auth.resendPhone(OtpType.Phone.SMS, normalized)
        }
    }

    override suspend fun verifyLoginCode(phone: String, code: String): Result<AuthSession> {
        val normalized = PhoneNumber.normalize(phone)
            ?: return Result.Error(AppError.Validation(PhoneNumber.INVALID_MESSAGE))
        if (!OtpCode.isValid(code)) {
            return Result.Error(AppError.Validation(OtpCode.INVALID_MESSAGE))
        }
        return when (val verified = request {
            client.auth.verifyPhoneOtp(OtpType.Phone.SMS, normalized, code.trim())
        }) {
            is Result.Error -> verified
            is Result.Success -> buildSession()
        }
    }

    override suspend fun currentSession(): Result<AuthSession?> =
        if (client.auth.currentUserOrNull() == null) Result.Success(null) else buildSession()

    override suspend fun signOut(): Result<Unit> = request {
        client.auth.signOut()
    }

    override suspend fun restoreSession(): Result<AuthSession?> {
        client.auth.awaitInitialization()
        return currentSession()
    }

    private suspend fun buildSession(): Result<AuthSession> {
        val info = client.auth.currentUserOrNull()
            ?: return Result.Error(AppError.Unauthorized())
        val stored = when (val result = users.find(info.id)) {
            is Result.Error -> return result
            is Result.Success -> result.data
                ?: return Result.Error(AppError.NotFound("Tu cuenta todavía no está disponible"))
        }
        // El teléfono de contacto se sincroniza con Auth (columna propia del usuario).
        val user = if (info.phone != null && info.phone != stored.phone) {
            when (val updated = users.update(stored.id, stored.copy(phone = info.phone))) {
                is Result.Success -> updated.data
                is Result.Error -> stored
            }
        } else {
            stored
        }
        val profile = when (val result = profiles.find(user.id)) {
            is Result.Error -> return result
            is Result.Success -> result.data ?: ensureProfile(user.id, user.phone)
        }
        val accountKind = deviceAccounts.resolveAccountKind(user.id)
        return Result.Success(AuthSession(user = user, profile = profile, accountKind = accountKind))
    }

    private suspend fun ensureProfile(userId: String, phone: String?): Profile =
        when (val created = profiles.create(Profile(
            userId = userId,
            displayName = ProfileDefaults.displayNameFor(phone)
        ))) {
            is Result.Success -> created.data
            is Result.Error -> Profile(
                userId = userId,
                displayName = ProfileDefaults.displayNameFor(phone)
            )
        }
}
