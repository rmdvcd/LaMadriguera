package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Review

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ReviewsRepository : InsertRepository<Review>
