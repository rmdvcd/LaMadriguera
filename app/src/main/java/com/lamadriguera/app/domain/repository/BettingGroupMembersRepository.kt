package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.BettingGroupMember

/** Membership changes are mediated by owner/public-join RPCs in BettingRepository. */
interface BettingGroupMembersRepository : ReadRepository<BettingGroupMember>
