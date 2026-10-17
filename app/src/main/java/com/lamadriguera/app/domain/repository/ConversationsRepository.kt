package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Conversation

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ConversationsRepository : MutableRepository<Conversation>
