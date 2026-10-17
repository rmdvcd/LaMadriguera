package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.IdentityVerification

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface IdentityVerificationsRepository : InsertRepository<IdentityVerification>
