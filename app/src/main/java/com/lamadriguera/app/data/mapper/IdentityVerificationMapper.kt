package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.IdentityDocRef
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.data.remote.supabase.row.IdentityVerificationRow
import java.time.Instant
import java.time.LocalDate

fun IdentityVerificationRow.toDomain(): IdentityVerification = IdentityVerification(
    id = id,
    userId = userId,
    docType = docType,
    docRef = docRef.decodeBytea(),
    fullName = fullName,
    birthdate = LocalDate.parse(birthdate),
    status = status,
    verifiedBy = verifiedBy,
    verifiedAt = verifiedAt?.let(Instant::parse),
    retentionUntil = Instant.parse(retentionUntil)
)

fun IdentityVerification.toRow(): IdentityVerificationRow = IdentityVerificationRow(
    id = id,
    userId = userId,
    docType = docType,
    docRef = docRef.encodeBytea(),
    fullName = fullName,
    birthdate = birthdate.toString(),
    status = status,
    verifiedBy = verifiedBy,
    verifiedAt = verifiedAt?.toString(),
    retentionUntil = retentionUntil.toString()
)

/**
 * Codifica la referencia a los documentos (rutas de Storage) que se guarda en
 * identity_verifications.doc_ref. Solo rutas: los bytes del documento nunca se
 * persisten en PostgREST.
 */
object IdentityDocRefCodec {

    private const val FRONT_KEY = "front"
    private const val BACK_KEY = "back"

    fun encode(ref: IdentityDocRef): ByteArray =
        """{"$FRONT_KEY":"${ref.frontPath}","$BACK_KEY":"${ref.backPath}"}""".toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): IdentityDocRef {
        val text = bytes.toString(Charsets.UTF_8)
        return IdentityDocRef(
            frontPath = extract(text, FRONT_KEY),
            backPath = extract(text, BACK_KEY)
        )
    }

    private fun extract(json: String, key: String): String {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)
        requireNotNull(match) { "Invalid identity document reference" }
        return match.groupValues[1]
    }
}
