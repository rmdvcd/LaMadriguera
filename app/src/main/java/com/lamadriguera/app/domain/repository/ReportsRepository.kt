package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Report

/** Access is additionally enforced by PostgreSQL grants and RLS. */
interface ReportsRepository : InsertRepository<Report>
