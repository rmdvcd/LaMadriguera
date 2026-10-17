package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.User

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface UsersRepository : UpdateRepository<User>
