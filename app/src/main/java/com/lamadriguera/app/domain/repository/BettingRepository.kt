package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Bet

/**
 * Contrato de apuestas peer-to-peer.
 * La verificación de saldo y reglas de emparejamiento se implementan como casos de uso.
 */
interface BettingRepository {

    suspend fun getOpenBets(): Result<List<Bet>>

    suspend fun getBet(betId: String): Result<Bet>

    suspend fun createBet(bet: Bet): Result<Bet>

    suspend fun joinBet(betId: String): Result<Bet>

    suspend fun getMyBets(): Result<List<Bet>>
}
