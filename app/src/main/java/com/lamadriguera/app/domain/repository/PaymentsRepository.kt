package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Payment

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface PaymentsRepository : InsertRepository<Payment>
