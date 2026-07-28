package com.nvnhan0810.backgrounddemo.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Snapshot tin nhắn đã xử lý (Telegram / sau này Viber).
 * messageKey = "telegram:{chatId}:{messageId}" — dùng để tìm bản ghi khi khách SỬA tin.
 */
@Entity(
    tableName = "inbound_messages",
    indices = [Index(value = ["messageKey"], unique = true)]
)
data class InboundMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageKey: String,
    val platform: String,
    val chatId: String,
    val messageId: String,
    val customerName: String,
    val rawText: String,
    /** JSON list ParsedLine: [{"code":"A12","qty":5.0}, ...] */
    val parsedJson: String,
    val editCount: Int = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
