package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.domain.model.Bet

/** Bets are read-only through this table API; changes must use BettingRepository RPCs. */
interface BetsRepository : ReadRepository<Bet>
