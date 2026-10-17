package com.lamadriguera.app.domain.model

import java.time.Instant
import java.time.LocalDate

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class IdentityVerification(
    val id: String,
    val userId: String,
    val docType: String,
    /** Referencia cifrada/opaca a los documentos; nunca los bytes del documento. */
    val docRef: ByteArray,
    val fullName: String,
    val birthdate: LocalDate,
    val status: IdentityVerificationStatus = IdentityVerificationStatus.PENDING,
    val verifiedBy: String? = null,
    val verifiedAt: Instant? = null,
    val retentionUntil: Instant
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityVerification) return false
        return id == other.id &&
            userId == other.userId &&
            docType == other.docType &&
            docRef.contentEquals(other.docRef) &&
            fullName == other.fullName &&
            birthdate == other.birthdate &&
            status == other.status &&
            verifiedBy == other.verifiedBy &&
            verifiedAt == other.verifiedAt &&
            retentionUntil == other.retentionUntil
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + docType.hashCode()
        result = 31 * result + docRef.contentHashCode()
        result = 31 * result + fullName.hashCode()
        result = 31 * result + birthdate.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (verifiedBy?.hashCode() ?: 0)
        result = 31 * result + (verifiedAt?.hashCode() ?: 0)
        result = 31 * result + retentionUntil.hashCode()
        return result
    }
}

/**
 * Solicitud de verificación de identidad: fotos del carnet (frente y reverso),
 * nombre completo y fecha de nacimiento. El repositorio sube las imágenes al
 * bucket privado y crea la fila en estado [IdentityVerificationStatus.PENDING].
 */
data class IdentityVerificationRequest(
    val fullName: String,
    val birthdate: LocalDate,
    val frontImage: ByteArray,
    val backImage: ByteArray,
    val docType: String = DOC_TYPE_CARNET
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityVerificationRequest) return false
        return fullName == other.fullName &&
            birthdate == other.birthdate &&
            frontImage.contentEquals(other.frontImage) &&
            backImage.contentEquals(other.backImage) &&
            docType == other.docType
    }

    override fun hashCode(): Int {
        var result = fullName.hashCode()
        result = 31 * result + birthdate.hashCode()
        result = 31 * result + frontImage.contentHashCode()
        result = 31 * result + backImage.contentHashCode()
        result = 31 * result + docType.hashCode()
        return result
    }

    companion object {
        const val DOC_TYPE_CARNET = "carnet"
    }
}

/** Rutas de Storage de las dos caras del documento, dentro de doc_ref. */
data class IdentityDocRef(
    val frontPath: String,
    val backPath: String
)
