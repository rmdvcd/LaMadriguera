package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Conversation
import com.lamadriguera.app.data.remote.supabase.row.ConversationRow

fun ConversationRow.toDomain(): Conversation = Conversation(
    id = id,
    listingId = listingId,
    participantA = participantA,
    participantB = participantB,
    revealPolicy = revealPolicy,
    sessionKeyWrapped = sessionKeyWrapped?.decodeBytea(),
    disputeKeySplitA = disputeKeySplitA?.decodeBytea(),
    disputeKeySplitB = disputeKeySplitB?.decodeBytea(),
    status = status
)

fun Conversation.toRow(): ConversationRow = ConversationRow(
    id = id,
    listingId = listingId,
    participantA = participantA,
    participantB = participantB,
    revealPolicy = revealPolicy,
    sessionKeyWrapped = sessionKeyWrapped?.encodeBytea(),
    disputeKeySplitA = disputeKeySplitA?.encodeBytea(),
    disputeKeySplitB = disputeKeySplitB?.encodeBytea(),
    status = status
)
