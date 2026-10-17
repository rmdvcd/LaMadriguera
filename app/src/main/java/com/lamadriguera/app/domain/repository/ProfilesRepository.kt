package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Profile

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ProfilesRepository : MutableRepository<Profile>
