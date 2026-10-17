package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result

/** Paged access to rows visible under the current Supabase session's RLS. */
interface ReadRepository<T> {
    suspend fun list(page: Int = 0, pageSize: Int = 50): Result<List<T>>
    /** A row hidden by RLS is indistinguishable from a missing row. */
    suspend fun find(id: String): Result<T?>
}

interface InsertRepository<T> : ReadRepository<T> {
    /** Server-owned columns are omitted; the returned value is the persisted row. */
    suspend fun create(model: T): Result<T>
}

interface UpdateRepository<T> : ReadRepository<T> {
    /** Only client-editable columns are sent. Primary keys/ownership cannot change. */
    suspend fun update(id: String, model: T): Result<T>
}

interface MutableRepository<T> : InsertRepository<T>, UpdateRepository<T>

interface DeleteRepository<T> : ReadRepository<T> {
    suspend fun delete(id: String): Result<Unit>
}
