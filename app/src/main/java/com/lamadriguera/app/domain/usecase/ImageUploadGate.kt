package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.repository.ImageModerationScanner
import com.lamadriguera.app.domain.repository.ImageScanResult
import com.lamadriguera.app.domain.repository.ImageVerdict
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puerta de subida de imágenes de contenido público (fotos de listings,
 * evidencias de apuestas). Corre NSFW.js en cliente ANTES de subir:
 * si el resultado es dudoso o inseguro, la imagen NO se sube.
 *
 * El chat privado queda fuera: la plataforma no modera contenido privado y la
 * conversación va cifrada de extremo a extremo.
 */
@Singleton
class ImageUploadGate @Inject constructor(
    private val scanner: ImageModerationScanner
) {

    sealed interface Outcome {
        /** La imagen puede subirse. */
        data object Allowed : Outcome

        /** Dudosa o insegura: no se sube, con mensaje para la persona. */
        data class Refused(val message: String, val result: ImageScanResult) : Outcome
    }

    suspend fun check(bytes: ByteArray): Result<Outcome> {
        if (bytes.isEmpty()) {
            return Result.Success(Outcome.Refused("La imagen está vacía.", ImageScanResult(ImageVerdict.UNSAFE)))
        }
        return when (val scan = scanner.scan(bytes)) {
            is Result.Error -> Result.Error(scan.throwable, scan.message, scan.code)
            is Result.Success -> {
                val result = scan.data
                val outcome = if (result.blocksUpload) {
                    Outcome.Refused(
                        message = when (result.verdict) {
                            ImageVerdict.UNSAFE -> "La imagen contiene contenido para adultos y no se subirá."
                            else -> "La imagen parece sensible y no se subirá. Elige otra foto."
                        },
                        result = result
                    )
                } else {
                    Outcome.Allowed
                }
                Result.Success(outcome)
            }
        }
    }
}
