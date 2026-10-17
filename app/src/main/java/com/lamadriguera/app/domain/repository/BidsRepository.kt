package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Bid

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface BidsRepository : InsertRepository<Bid>
