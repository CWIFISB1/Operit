package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity

// SQLite LENGTH/SUBSTR count characters, and this bound keeps every returned text row well below CursorWindow size.
private const val CONTENT_CHUNK_CHARACTER_COUNT = 65_536

/**
 * 单次物化（把分块正文拼回完整消息）允许消耗的总字符预算。
 *
 * 为什么需要：607MB 的聊天库里可能存在单条几百 MB 的正文。旧实现无条件
 * 逐块 append，堆在拼到一半时抛 OutOfMemoryError（栈顶
 * ChatContentDao.materializeMessage:435 StringBuilder.append）。
 * 这里设一个硬预算：预算内的消息完整还原，预算耗尽后的消息只保留已读到
 * 的部分（至少保证首块可显示），宁可截断也不崩。
 */
private const val MAX_MATERIALIZE_CHARS = 6_000_000

/** 预算耗尽后，剩余消息最多保留的字符数（首块）。 */
private const val MATERIALIZE_FALLBACK_CHARS = CONTENT_CHUNK_CHARACTER_COUNT

private const val MESSAGE_CONTENT_ROW_QUERY =
    """
    SELECT
        messageId,
        chatId,
        sender,
        SUBSTR(content, 1, $CONTENT_CHUNK_CHARACTER_COUNT) AS content,
        timestamp,
        orderIndex,
        roleName,
        selectedVariantIndex,
        provider,
        modelName,
        inputTokens,
        outputTokens,
        cachedInputTokens,
        sentAt,
        outputDurationMs,
        waitDurationMs,
        completedAt,
        displayMode,
        isFavorite,
        LENGTH(content) AS contentCharacterCount
    FROM messages
    """

private const val MESSAGE_VARIANT_CONTENT_ROW_QUERY =
    """
    SELECT
        variantId,
        chatId,
        messageTimestamp,
        variantIndex,
        SUBSTR(content, 1, $CONTENT_CHUNK_CHARACTER_COUNT) AS content,
        roleName,
        provider,
        modelName,
        inputTokens,
        outputTokens,
        cachedInputTokens,
        sentAt,
        outputDurationMs,
        waitDurationMs,
        completedAt,
        LENGTH(content) AS contentCharacterCount
    FROM message_variants
    """

data class MessageContentRow(
    @Embedded val message: MessageEntity,
    val contentCharacterCount: Long,
)

data class MessageVariantContentRow(
    @Embedded val variant: MessageVariantEntity,
    val contentCharacterCount: Long,
)

data class ChatContentCharacterCount(
    val chatId: String,
    val contentCharacterCount: Long,
)

/** Reads message text in bounded rows so a single large message cannot overflow CursorWindow. */
@Dao
abstract class ChatContentDao {
    @Query(MESSAGE_CONTENT_ROW_QUERY + " WHERE chatId = :chatId ORDER BY timestamp ASC")
    protected abstract suspend fun queryMessagesForChat(chatId: String): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp >= :startTimestampInclusive ORDER BY timestamp ASC"
    )
    protected abstract suspend fun queryMessagesForChatFromTimestampAsc(
        chatId: String,
        startTimestampInclusive: Long,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp >= :startTimestampInclusive" +
            " AND timestamp <= :endTimestampInclusive ORDER BY timestamp ASC"
    )
    protected abstract suspend fun queryMessagesForChatWindowAsc(
        chatId: String,
        startTimestampInclusive: Long,
        endTimestampInclusive: Long,
    ): List<MessageContentRow>

    @Query(MESSAGE_CONTENT_ROW_QUERY + " WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT :limit")
    protected abstract suspend fun queryMessagesForChatAsc(
        chatId: String,
        limit: Int,
    ): List<MessageContentRow>

    @Query(MESSAGE_CONTENT_ROW_QUERY + " WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit")
    protected abstract suspend fun queryMessagesForChatDesc(
        chatId: String,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset"
    )
    protected abstract suspend fun queryMessagesForChatAscRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset"
    )
    protected abstract suspend fun queryMessagesForChatDescRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp > :afterTimestampExclusive" +
            " ORDER BY timestamp ASC LIMIT :limit"
    )
    protected abstract suspend fun queryMessagesForChatAfterTimestampExclusiveAsc(
        chatId: String,
        afterTimestampExclusive: Long,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId" +
            " AND (:afterTimestampExclusive IS NULL OR timestamp > :afterTimestampExclusive)" +
            " AND (:beforeTimestampExclusive IS NULL OR timestamp < :beforeTimestampExclusive)" +
            " AND (:upToTimestampInclusive IS NULL OR timestamp <= :upToTimestampInclusive)" +
            " ORDER BY timestamp ASC"
    )
    protected abstract suspend fun queryMessagesForChatInRangeAsc(
        chatId: String,
        afterTimestampExclusive: Long?,
        beforeTimestampExclusive: Long?,
        upToTimestampInclusive: Long?,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp <= :maxTimestamp" +
            " ORDER BY timestamp DESC LIMIT :limit"
    )
    protected abstract suspend fun queryMessagesForChatBeforeTimestampDesc(
        chatId: String,
        maxTimestamp: Long,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp < :beforeTimestampExclusive" +
            " ORDER BY timestamp DESC LIMIT :limit"
    )
    protected abstract suspend fun queryMessagesForChatBeforeTimestampExclusiveDesc(
        chatId: String,
        beforeTimestampExclusive: Long,
        limit: Int,
    ): List<MessageContentRow>

    @Query(
        MESSAGE_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND timestamp = :timestamp LIMIT 1"
    )
    protected abstract suspend fun queryMessageByTimestamp(
        chatId: String,
        timestamp: Long,
    ): MessageContentRow?

    @Query(
        "SELECT SUBSTR(content, :startCharacter, :characterCount)" +
            " FROM messages WHERE messageId = :messageId"
    )
    protected abstract suspend fun queryMessageContentChunk(
        messageId: Long,
        startCharacter: Long,
        characterCount: Int,
    ): String?

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId ORDER BY messageTimestamp ASC, variantIndex ASC"
    )
    protected abstract suspend fun queryVariantsForChat(chatId: String): List<MessageVariantContentRow>

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId" +
            " AND messageTimestamp >= :minTimestamp" +
            " AND messageTimestamp <= :maxTimestamp" +
            " ORDER BY messageTimestamp ASC, variantIndex ASC"
    )
    protected abstract suspend fun queryVariantsForMessageRange(
        chatId: String,
        minTimestamp: Long,
        maxTimestamp: Long,
    ): List<MessageVariantContentRow>

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND messageTimestamp = :messageTimestamp" +
            " ORDER BY variantIndex ASC"
    )
    protected abstract suspend fun queryVariantsForMessage(
        chatId: String,
        messageTimestamp: Long,
    ): List<MessageVariantContentRow>

    @Query(
        MESSAGE_VARIANT_CONTENT_ROW_QUERY +
            " WHERE chatId = :chatId AND messageTimestamp = :messageTimestamp" +
            " AND variantIndex = :variantIndex LIMIT 1"
    )
    protected abstract suspend fun queryVariantForMessage(
        chatId: String,
        messageTimestamp: Long,
        variantIndex: Int,
    ): MessageVariantContentRow?

    @Query(
        "SELECT SUBSTR(content, :startCharacter, :characterCount)" +
            " FROM message_variants WHERE variantId = :variantId"
    )
    protected abstract suspend fun queryMessageVariantContentChunk(
        variantId: Long,
        startCharacter: Long,
        characterCount: Int,
    ): String?

    @Query(
        """
        SELECT
            chats.id AS chatId,
            COALESCE(
                SUM(
                    CASE
                        WHEN messages.selectedVariantIndex = 0 THEN LENGTH(messages.content)
                        ELSE LENGTH(selectedVariant.content)
                    END
                ),
                0
            ) AS contentCharacterCount
        FROM chats
        LEFT JOIN messages
            ON messages.chatId = chats.id
        LEFT JOIN message_variants AS selectedVariant
            ON selectedVariant.chatId = messages.chatId
            AND selectedVariant.messageTimestamp = messages.timestamp
            AND selectedVariant.variantIndex = messages.selectedVariantIndex
        GROUP BY chats.id
        """
    )
    abstract suspend fun getSelectedContentCharacterCountsByChat(): List<ChatContentCharacterCount>

    @Transaction
    open suspend fun getMessagesForChat(chatId: String): List<MessageEntity> =
        materializeMessages(queryMessagesForChat(chatId))

    @Transaction
    open suspend fun getMessagesForChatFromTimestampAsc(
        chatId: String,
        startTimestampInclusive: Long,
    ): List<MessageEntity> =
        materializeMessages(queryMessagesForChatFromTimestampAsc(chatId, startTimestampInclusive))

    @Transaction
    open suspend fun getMessagesForChatWindowAsc(
        chatId: String,
        startTimestampInclusive: Long,
        endTimestampInclusive: Long,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatWindowAsc(chatId, startTimestampInclusive, endTimestampInclusive)
        )

    @Transaction
    open suspend fun getMessagesForChatAsc(chatId: String, limit: Int): List<MessageEntity> =
        materializeMessages(queryMessagesForChatAsc(chatId, limit))

    @Transaction
    open suspend fun getMessagesForChatDesc(chatId: String, limit: Int): List<MessageEntity> =
        materializeMessages(queryMessagesForChatDesc(chatId, limit))

    @Transaction
    open suspend fun getMessagesForChatAscRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> = materializeMessages(queryMessagesForChatAscRange(chatId, offset, limit))

    @Transaction
    open suspend fun getMessagesForChatDescRange(
        chatId: String,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> = materializeMessages(queryMessagesForChatDescRange(chatId, offset, limit))

    @Transaction
    open suspend fun getMessagesForChatAfterTimestampExclusiveAsc(
        chatId: String,
        afterTimestampExclusive: Long,
        limit: Int,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatAfterTimestampExclusiveAsc(chatId, afterTimestampExclusive, limit)
        )

    @Transaction
    open suspend fun getMessagesForChatInRangeAsc(
        chatId: String,
        afterTimestampExclusive: Long?,
        beforeTimestampExclusive: Long?,
        upToTimestampInclusive: Long?,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatInRangeAsc(
                chatId,
                afterTimestampExclusive,
                beforeTimestampExclusive,
                upToTimestampInclusive,
            )
        )

    @Transaction
    open suspend fun getMessagesForChatBeforeTimestampDesc(
        chatId: String,
        maxTimestamp: Long,
        limit: Int,
    ): List<MessageEntity> =
        materializeMessages(queryMessagesForChatBeforeTimestampDesc(chatId, maxTimestamp, limit))

    @Transaction
    open suspend fun getMessagesForChatBeforeTimestampExclusiveDesc(
        chatId: String,
        beforeTimestampExclusive: Long,
        limit: Int,
    ): List<MessageEntity> =
        materializeMessages(
            queryMessagesForChatBeforeTimestampExclusiveDesc(chatId, beforeTimestampExclusive, limit)
        )

    @Transaction
    open suspend fun getMessageByTimestamp(chatId: String, timestamp: Long): MessageEntity? =
        queryMessageByTimestamp(chatId, timestamp)?.let { row ->
            val holder = ArrayList<com.ai.assistance.operit.data.model.MessageEntity>(1)
            materializeMessageInto(row, MAX_MATERIALIZE_CHARS, holder)
            holder.firstOrNull()
        }

    @Transaction
    open suspend fun getVariantsForChat(chatId: String): List<MessageVariantEntity> =
        materializeVariants(queryVariantsForChat(chatId))

    @Transaction
    open suspend fun getVariantsForMessages(
        chatId: String,
        messageTimestamps: List<Long>,
    ): List<MessageVariantEntity> {
        if (messageTimestamps.isEmpty()) {
            return emptyList()
        }

        val requestedTimestamps = messageTimestamps.toHashSet()
        val minTimestamp = messageTimestamps.minOrNull() ?: return emptyList()
        val maxTimestamp = messageTimestamps.maxOrNull() ?: return emptyList()

        // Preserve exact timestamp-set semantics while avoiding Room expanding a large list into SQLite bind variables.
        val rows =
            queryVariantsForMessageRange(
                chatId = chatId,
                minTimestamp = minTimestamp,
                maxTimestamp = maxTimestamp,
            ).filter { row -> row.variant.messageTimestamp in requestedTimestamps }
        return materializeVariants(rows)
    }

    @Transaction
    open suspend fun getVariantsForMessage(
        chatId: String,
        messageTimestamp: Long,
    ): List<MessageVariantEntity> =
        materializeVariants(queryVariantsForMessage(chatId, messageTimestamp))

    @Transaction
    open suspend fun getVariantForMessage(
        chatId: String,
        messageTimestamp: Long,
        variantIndex: Int,
    ): MessageVariantEntity? =
        queryVariantForMessage(chatId, messageTimestamp, variantIndex)?.let { row ->
            val holder = ArrayList<MessageVariantEntity>(1)
            materializeVariantInto(row, MAX_MATERIALIZE_CHARS, holder)
            holder.firstOrNull()
        }

    private suspend fun materializeMessages(rows: List<MessageContentRow>): List<MessageEntity> {
        if (rows.isEmpty()) return emptyList()
        var budget = MAX_MATERIALIZE_CHARS
        val out = ArrayList<MessageEntity>(rows.size)
        for (row in rows) {
            val used = materializeMessageInto(row, budget, out)
            budget -= used
        }
        return out
    }

    /**
     * 物化单条消息并直接写入 out，返回本次消耗的字符数。
     *
     * budget <= 0 表示全局预算已耗尽，此时只保留首块（MATERIALIZE_FALLBACK_CHARS），
     * 保证 UI 仍能看到开头，而不是整条消失或直接 OOM。
     */
    private suspend fun materializeMessageInto(
        row: MessageContentRow,
        budget: Int,
        out: MutableList<MessageEntity>,
    ): Int {
        val totalChars = row.contentCharacterCount
        if (totalChars <= CONTENT_CHUNK_CHARACTER_COUNT) {
            out.add(row.message)
            return row.message.content.length
        }

        // 本次允许还原到的字符位置上限
        val allowedChars: Long = when {
            budget <= 0 -> MATERIALIZE_FALLBACK_CHARS.toLong()
            budget.toLong() >= totalChars -> totalChars
            else -> budget.toLong().coerceAtLeast(MATERIALIZE_FALLBACK_CHARS.toLong())
        }

        val content = StringBuilder(row.message.content)
        var startCharacter = CONTENT_CHUNK_CHARACTER_COUNT.toLong() + 1L
        var consumed = row.message.content.length
        while (startCharacter <= allowedChars) {
            val chunk = queryMessageContentChunk(
                row.message.messageId,
                startCharacter,
                CONTENT_CHUNK_CHARACTER_COUNT,
            )
            // 预算截断时数据库里仍然有数据，只是我们不再读；只有真正读不到才算异常
            if (chunk == null) break
            if (chunk.isEmpty()) break
            content.append(chunk)
            consumed += chunk.length
            startCharacter += CONTENT_CHUNK_CHARACTER_COUNT
        }
        out.add(row.message.copy(content = content.toString()))
        return consumed
    }

    private suspend fun materializeVariants(
        rows: List<MessageVariantContentRow>
    ): List<MessageVariantEntity> {
        if (rows.isEmpty()) return emptyList()
        var budget = MAX_MATERIALIZE_CHARS
        val out = ArrayList<MessageVariantEntity>(rows.size)
        for (row in rows) {
            val used = materializeVariantInto(row, budget, out)
            budget -= used
        }
        return out
    }

    private suspend fun materializeVariantInto(
        row: MessageVariantContentRow,
        budget: Int,
        out: MutableList<MessageVariantEntity>,
    ): Int {
        val totalChars = row.contentCharacterCount
        if (totalChars <= CONTENT_CHUNK_CHARACTER_COUNT) {
            out.add(row.variant)
            return row.variant.content.length
        }

        val allowedChars: Long = when {
            budget <= 0 -> MATERIALIZE_FALLBACK_CHARS.toLong()
            budget.toLong() >= totalChars -> totalChars
            else -> budget.toLong().coerceAtLeast(MATERIALIZE_FALLBACK_CHARS.toLong())
        }

        val content = StringBuilder(row.variant.content)
        var startCharacter = CONTENT_CHUNK_CHARACTER_COUNT.toLong() + 1L
        var consumed = row.variant.content.length
        while (startCharacter <= allowedChars) {
            val chunk = queryMessageVariantContentChunk(
                row.variant.variantId,
                startCharacter,
                CONTENT_CHUNK_CHARACTER_COUNT,
            )
            if (chunk == null) break
            if (chunk.isEmpty()) break
            content.append(chunk)
            consumed += chunk.length
            startCharacter += CONTENT_CHUNK_CHARACTER_COUNT
        }
        out.add(row.variant.copy(content = content.toString()))
        return consumed
    }
}
