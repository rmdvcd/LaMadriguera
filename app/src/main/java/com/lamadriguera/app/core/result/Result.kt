package com.lamadriguera.app.core.result

/**
 * Result propio de la app. No usar kotlin.Result.
 *
 * Solo representa el desenlace de una operación (éxito o error).
 * Los estados de carga pertenecen a [UiState], no al dominio.
 */
sealed interface Result<out T> {

    data class Success<T>(val data: T) : Result<T>

    data class Error(
        val throwable: Throwable? = null,
        val message: String? = throwable?.message,
        val code: Int? = null
    ) : Result<Nothing>
}

/**
 * Helpers de extensión para mapear Result.
 */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Error -> this
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (Result.Error) -> Unit): Result<T> {
    if (this is Result.Error) action(this)
    return this
}

fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success)?.data

fun <T> Result<T>.getOrDefault(default: T): T = (this as? Result.Success)?.data ?: default
