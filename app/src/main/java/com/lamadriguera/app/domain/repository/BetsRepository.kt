package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Bet

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface BetsRepository : InsertRepository<Bet>
