package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Category

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface CategoriesRepository : MutableRepository<Category>, DeleteRepository<Category>
