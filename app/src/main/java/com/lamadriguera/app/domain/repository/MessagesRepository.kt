package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Message

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface MessagesRepository : InsertRepository<Message>
