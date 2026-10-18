package com.lamadriguera.app.domain

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AiModerationResult
import com.lamadriguera.app.domain.model.ModeratedContent
import com.lamadriguera.app.domain.model.ModerationAction
import com.lamadriguera.app.domain.model.ModerationCategory
import com.lamadriguera.app.domain.model.ModerationDecision
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.ModerationReviewStatus
import com.lamadriguera.app.domain.model.ModerationSource
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.Sanction
import com.lamadriguera.app.domain.model.SanctionRequest
import com.lamadriguera.app.domain.repository.ModerationApi
import com.lamadriguera.app.domain.repository.ModerationRepository
import com.lamadriguera.app.domain.usecase.ModerateContentUseCase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerateContentUseCaseTest {

    private val now = Instant.parse("2026-10-18T12:00:00Z")

    private class FakeModerationApi(var result: Result<AiModerationResult>) : ModerationApi {
        var lastText: String? = null
        override suspend fun moderateText(text: String): Result<AiModerationResult> {
            lastText = text
            return result
        }
    }

    private class FakeModerationRepository : ModerationRepository {
        val blocks = mutableListOf<ModerationAction>()
        val flagged = mutableListOf<ModerationReview>()

        override suspend fun reviewQueue(status: ModerationReviewStatus?, minSeverity: Int) =
            Result.Success(flagged.toList())

        override suspend fun groupReviewQueue(groupId: String) = Result.Success(flagged.toList())

        override suspend fun flagForReview(
            targetType: ModerationTargetType,
            targetId: String,
            category: String,
            severity: Int,
            excerpt: String,
            evidence: kotlinx.serialization.json.JsonObject?,
            aiProvider: String?,
            aiScores: kotlinx.serialization.json.JsonObject?
        ): Result<ModerationReview> {
            val review = ModerationReview(
                id = "review-${flagged.size}",
                targetType = targetType,
                targetId = targetId,
                source = ModerationSource.AI,
                category = category,
                severity = severity,
                status = ModerationReviewStatus.PENDING,
                excerpt = excerpt,
                evidence = evidence,
                aiProvider = aiProvider,
                aiScores = aiScores,
                createdAt = now
            )
            flagged += review
            return Result.Success(review)
        }

        override suspend fun recordAiBlock(
            targetType: ModerationTargetType,
            targetId: String,
            category: String,
            severity: Int,
            reason: String,
            evidence: kotlinx.serialization.json.JsonObject?
        ): Result<ModerationAction> {
            val action = ModerationAction(
                id = "action-${blocks.size}",
                targetType = targetType.wireValue,
                targetId = targetId,
                source = ModerationSource.AI,
                category = category,
                severity = severity.toShort(),
                action = "block",
                reason = reason,
                actorId = "user-1",
                createdAt = now,
                evidence = evidence
            )
            blocks += action
            return Result.Success(action)
        }

        override suspend fun applySanction(request: SanctionRequest): Result<ModerationAction> =
            Result.Error(AppError.Server())

        override suspend fun dismissReview(reviewId: String, reason: String): Result<ModerationReview> =
            Result.Error(AppError.Server())
    }

    private val content = ModeratedContent(
        targetType = ModerationTargetType.LISTING,
        title = "Vendo refrigerador",
        description = "Refrigerador en buen estado, entrega en La Habana."
    )

    private fun useCase(
        api: FakeModerationApi,
        repository: FakeModerationRepository = FakeModerationRepository()
    ): Pair<ModerateContentUseCase, FakeModerationRepository> =
        ModerateContentUseCase(api, repository) to repository

    @Test
    fun cleanContentIsAllowed() = runBlocking {
        val api = FakeModerationApi(
            Result.Success(AiModerationResult(provider = "openai", flagged = false))
        )
        val (useCase, repository) = useCase(api)
        val decision = (useCase.moderate(content) as Result.Success).data
        assertTrue(decision is ModerationDecision.Allowed)
        assertTrue(repository.blocks.isEmpty())
        assertTrue(repository.flagged.isEmpty())
    }

    @Test
    fun severeAiCategoryBlocksAndLogsInModerationActions() = runBlocking {
        val api = FakeModerationApi(
            Result.Success(
                AiModerationResult(
                    provider = "llamaguard",
                    flagged = true,
                    categories = mapOf(ModerationCategory.VIOLENCE.wireValue to 0.99f)
                )
            )
        )
        val (useCase, repository) = useCase(api)
        val decision = (useCase.moderate(content) as Result.Success).data
        assertTrue(decision is ModerationDecision.Blocked)
        assertEquals(ModerationCategory.VIOLENCE, decision.category)
        assertTrue((decision as ModerationDecision.Blocked).logged)
        assertEquals(1, repository.blocks.size)
        assertEquals("block", repository.blocks.first().action)
        assertTrue(repository.flagged.isEmpty())

        // Una categoría grave marcada se bloquea aunque el score no sea alto.
        val lowScoreSevere = FakeModerationApi(
            Result.Success(
                AiModerationResult(
                    provider = "openai",
                    flagged = true,
                    categories = mapOf(ModerationCategory.HARASSMENT.wireValue to 0.4f)
                )
            )
        )
        val (useCase2, repository2) = useCase(lowScoreSevere)
        val decision2 = (useCase2.moderate(content) as Result.Success).data
        assertTrue(decision2 is ModerationDecision.Blocked)
        assertTrue((decision2 as ModerationDecision.Blocked).logged)
        assertEquals(1, repository2.blocks.size)
    }

    @Test
    fun doubtfulAiContentReachesHumanQueueOnlyAfterFlag() = runBlocking {
        val api = FakeModerationApi(
            Result.Success(
                AiModerationResult(
                    provider = "openai",
                    flagged = true,
                    categories = mapOf(ModerationCategory.SPAM.wireValue to 0.35f)
                )
            )
        )
        val (useCase, repository) = useCase(api)
        val decision = (useCase.moderate(content) as Result.Success).data
        assertTrue(decision is ModerationDecision.Doubtful)
        assertTrue(repository.blocks.isEmpty())

        // Solo tras publicar se registra en la cola humana.
        assertTrue(repository.flagged.isEmpty())
        useCase.flagDoubtful(
            targetType = ModerationTargetType.LISTING,
            targetId = "listing-1",
            decision = decision as ModerationDecision.Doubtful
        )
        assertEquals(1, repository.flagged.size)
        assertEquals("listing-1", repository.flagged.first().targetId)
    }

    @Test
    fun lowScoreBelowDoubtfulThresholdIsAllowedButAboveThresholdQueues() = runBlocking {
        val borderline = FakeModerationApi(
            Result.Success(
                AiModerationResult(
                    provider = "openai",
                    flagged = false,
                    categories = mapOf(ModerationCategory.OTHER.wireValue to ModerateContentUseCase.DOUBTFUL_SCORE)
                )
            )
        )
        val (useCase, _) = useCase(borderline)
        val decision = (useCase.moderate(content) as Result.Success).data
        assertTrue(decision is ModerationDecision.Doubtful)

        val quiet = FakeModerationApi(
            Result.Success(
                AiModerationResult(
                    provider = "openai",
                    flagged = false,
                    categories = mapOf(ModerationCategory.OTHER.wireValue to 0.05f)
                )
            )
        )
        val (useCase2, _) = useCase(quiet)
        val decision2 = (useCase2.moderate(content) as Result.Success).data
        assertTrue(decision2 is ModerationDecision.Allowed)
    }

    @Test
    fun unavailableApiFailsToHumanReviewNotSilentPublish() = runBlocking {
        val api = FakeModerationApi(Result.Error(AppError.Network()))
        val (useCase, repository) = useCase(api)
        val decision = (useCase.moderate(content) as Result.Success).data
        assertTrue(decision is ModerationDecision.Doubtful)
        assertFalse(decision is ModerationDecision.Blocked)
        assertTrue(repository.blocks.isEmpty())
    }

    @Test
    fun domainRuleViolationBlocksWithoutAiOrQueue() = runBlocking {
        val api = FakeModerationApi(
            Result.Success(AiModerationResult(provider = "none", flagged = false))
        )
        val (useCase, repository) = useCase(api)
        val phoneContent = content.copy(title = "Vendo por 53512345")
        val decision = (useCase.moderate(phoneContent) as Result.Success).data
        assertTrue(decision is ModerationDecision.Blocked)
        assertEquals(ModerationCategory.PHONE_LEAK, decision.category)
        // Reglas de dominio: rechazo claro en cliente, sin llamar a la IA ni encolar.
        assertEquals(null, api.lastText)
        assertTrue(repository.blocks.isEmpty())
        assertTrue(repository.flagged.isEmpty())
    }

    @Test
    fun gamblingContentIsBlockedAsSevereEvenBeforeAi() = runBlocking {
        val api = FakeModerationApi(
            Result.Success(AiModerationResult(provider = "none", flagged = false))
        )
        val (useCase, _) = useCase(api)
        val decision = (useCase.moderate(
            ModeratedContent(targetType = ModerationTargetType.GROUP_MESSAGE, body = "Juega la bolita segura")
        ) as Result.Success).data
        assertTrue(decision is ModerationDecision.Blocked)
        assertEquals(ModerationCategory.ILLEGAL_GAMBLING, decision.category)
    }
}
