package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RepositoryRequestTest {
    @Test fun returnsSuccess() = runBlocking {
        assertEquals(Result.Success(42), request { 42 })
    }

    @Test fun preservesDomainError() = runBlocking {
        val result = request<Nothing> { throw AppError.NotFound() }
        assertTrue((result as Result.Error).throwable is AppError.NotFound)
    }

    @Test fun validationDoesNotExposeSensitiveInput() = runBlocking {
        val result = request<Nothing> { throw IllegalArgumentException("sensitive value") }
        assertTrue((result as Result.Error).throwable is AppError.Validation)
        assertFalse(result.message.orEmpty().contains("sensitive"))
    }

    @Test fun mapsNetworkErrorsWithoutLeakingResponse() = runBlocking {
        val result = request<Nothing> { throw IOException("secret URL") }
        assertTrue((result as Result.Error).throwable is AppError.Network)
        assertFalse(result.message.orEmpty().contains("secret"))
    }

    @Test fun unexpectedErrorIsSanitized() = runBlocking {
        val result = request<Nothing> { throw IllegalStateException("secret response") }
        assertTrue((result as Result.Error).throwable is AppError.Unknown)
        assertFalse(result.message.orEmpty().contains("secret"))
    }

    @Test fun cancellationIsRethrown() = runBlocking {
        val cancelled = CancellationException("cancelled")
        try {
            request<Nothing> { throw cancelled }
            fail("Cancellation must not become a Result.Error")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }
}
