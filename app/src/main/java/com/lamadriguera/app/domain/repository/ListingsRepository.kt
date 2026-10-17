package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Listing

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ListingsRepository : MutableRepository<Listing>, DeleteRepository<Listing>
