package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.BettingGroupMember

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface BettingGroupMembersRepository : InsertRepository<BettingGroupMember>, DeleteRepository<BettingGroupMember>
