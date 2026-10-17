package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.model.IdentityVerificationRequest

/**
 * Verificación de identidad: solicitud con fotos del carnet en Storage (bucket
 * privado) y revisión administrativa. Access is additionally enforced by
 * PostgreSQL grants and RLS.
 */
interface IdentityVerificationsRepository : InsertRepository<IdentityVerification> {

    /** Última solicitud del usuario (la más reciente), o null si no hay. */
    suspend fun latestForUser(userId: String): Result<IdentityVerification?>

    /**
     * Sube frente y reverso del carnet al bucket privado y crea la fila en
     * estado pending. El usuario se toma de la sesión actual.
     */
    suspend fun submit(draft: IdentityVerificationRequest): Result<IdentityVerification>

    /**
     * Revisión administrativa: pasa la solicitud a approved o rejected.
     * Al aprobar, el backend promueve la cuenta (verification_level/status).
     */
    suspend fun review(id: String, approved: Boolean): Result<IdentityVerification>
}
