package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.BettingGroup

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface BettingGroupsRepository : MutableRepository<BettingGroup>
