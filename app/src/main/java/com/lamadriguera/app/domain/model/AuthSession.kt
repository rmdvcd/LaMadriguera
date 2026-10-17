package com.lamadriguera.app.domain.model

/**
 * Sesión de autenticación: usuario, perfil y clase de cuenta del dispositivo.
 *
 * Reglas de capacidad:
 * - Sin verificar identidad (`status = temp`): puede navegar, pero no crear
 *   grupos ni apostar.
 * - 1 teléfono (dispositivo) = 1 cuenta principal ([AccountKind.PRIMARY]); se
 *   permiten cuentas adicionales ([AccountKind.SECONDARY]), pero con
 *   verificación de identidad obligatoria para subastas y apuestas.
 */
data class AuthSession(
    val user: User,
    val profile: Profile?,
    val accountKind: AccountKind
) {
    val capabilities: UserCapabilities
        get() = UserCapabilities.of(user, accountKind)
}

/** Estado reactivo de la sesión para la UI. */
sealed interface AuthSessionState {
    /** Supabase Auth está restaurando la sesión guardada. */
    data object Initializing : AuthSessionState

    data object Unauthenticated : AuthSessionState

    data class Authenticated(val session: AuthSession) : AuthSessionState

    /** La sesión existe pero no se pudo cargar el perfil/usuario. */
    data class Error(val message: String) : AuthSessionState
}

/** Capacidades derivadas del estado del usuario y de la clase de cuenta. */
data class UserCapabilities(
    val status: UserStatus,
    val verificationLevel: VerificationLevel,
    val accountKind: AccountKind
) {
    /** Temporal o no: navegar siempre está permitido salvo baneo/suspensión. */
    val canNavigate: Boolean
        get() = status != UserStatus.SUSPENDED && status != UserStatus.BANNED

    /** Temp no puede crear grupos de apuestas. */
    val canCreateGroups: Boolean
        get() = status == UserStatus.ACTIVE

    /** Apostar exige identidad verificada (y más aún en cuentas adicionales). */
    val canBet: Boolean
        get() = status == UserStatus.ACTIVE &&
            (accountKind == AccountKind.PRIMARY || verificationLevel == VerificationLevel.IDENTITY)

    /**
     * Subastas (pujar/crear): la cuenta principal participa con solo verificar
     * el teléfono; las cuentas adicionales requieren identidad verificada.
     */
    val canAuction: Boolean
        get() = canNavigate &&
            (accountKind == AccountKind.PRIMARY || verificationLevel == VerificationLevel.IDENTITY)

    /** Sin verificar identidad: estado 'temp'. */
    val isTemp: Boolean
        get() = status == UserStatus.TEMP

    companion object {
        fun of(user: User, accountKind: AccountKind): UserCapabilities =
            UserCapabilities(
                status = user.status,
                verificationLevel = user.verificationLevel,
                accountKind = accountKind
            )
    }
}
