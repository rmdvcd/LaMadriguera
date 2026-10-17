package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.AccountKind

/**
 * Regla de cuentas por dispositivo: 1 teléfono = 1 cuenta principal; se permiten
 * cuentas adicionales (con verificación de identidad obligatoria para subastas
 * y apuestas). La primera cuenta que entra en el dispositivo es la principal.
 */
interface DeviceAccountRegistry {

    /** Resuelve y registra la clase de cuenta para [accountId] en este dispositivo. */
    suspend fun resolveAccountKind(accountId: String): AccountKind

    /** Identificador de la cuenta principal registrada en el dispositivo, si existe. */
    suspend fun primaryAccountId(): String?
}
