package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.ModerationAction

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ModerationActionsRepository : ReadRepository<ModerationAction>
