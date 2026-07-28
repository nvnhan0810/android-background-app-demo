package com.nvnhan0810.backgrounddemo.ledger

import android.content.Context
import com.nvnhan0810.backgrounddemo.LearningLog
import com.nvnhan0810.backgrounddemo.db.DatabaseProvider
import com.nvnhan0810.backgrounddemo.db.InboundMessageEntity
import com.nvnhan0810.backgrounddemo.db.LedgerEntryEntity
import com.nvnhan0810.backgrounddemo.telegram.TelegramConfig

/**
 * Xử lý nghiệp vụ sổ cái: tin mới / tin sửa / tất toán.
 * Gọi tuần tự từ LedgerQueue (single-writer).
 */
class LedgerProcessor(context: Context) {

    private val app = context.applicationContext
    private val db = DatabaseProvider.get(app)

    fun messageKey(platform: String, chatId: String, messageId: String): String =
        "$platform:$chatId:$messageId"

    /**
     * @return chuỗi xác nhận để auto-reply (null nếu bỏ qua)
     */
    fun applyInbound(
        platform: String,
        chatId: String,
        messageId: String,
        customerName: String,
        rawText: String,
        isEdit: Boolean
    ): String {
        val key = messageKey(platform, chatId, messageId)
        val lines = MessageParser.parse(rawText)
        val ratio = TelegramConfig.getPriceRatio(app)
        val now = System.currentTimeMillis()

        var reply = ""
        db.runInTransaction {
            val existing = db.inboundMessageDao().findByKey(key)
            if (!isEdit) {
                if (existing != null) {
                    LearningLog.w(TAG, "Duplicate new message ignored key=$key")
                    reply = "DUP | đã xử lý trước đó"
                    return@runInTransaction
                }
                if (lines.isEmpty()) {
                    LearningLog.w(TAG, "Parse empty for NEW key=$key raw=${rawText.take(80)}")
                    db.inboundMessageDao().insert(
                        InboundMessageEntity(
                            messageKey = key,
                            platform = platform,
                            chatId = chatId,
                            messageId = messageId,
                            customerName = customerName,
                            rawText = rawText,
                            parsedJson = "[]",
                            editCount = 0,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                    reply = "SKIP | không khớp cú pháp CODE QTY"
                    return@runInTransaction
                }
                applyLines(lines, ratio, chatId, customerName, key, "INBOUND", now)
                db.inboundMessageDao().insert(
                    InboundMessageEntity(
                        messageKey = key,
                        platform = platform,
                        chatId = chatId,
                        messageId = messageId,
                        customerName = customerName,
                        rawText = rawText,
                        parsedJson = MessageParser.toJson(lines),
                        editCount = 0,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
                TelegramConfig.setSelectedCustomer(app, chatId, customerName)
                val summary = MessageParser.summarize(lines)
                LearningLog.i(TAG, "INBOUND key=$key $summary")
                reply = "OK | $summary | msg#$messageId"
                return@runInTransaction
            }

            // EDIT
            if (existing == null) {
                LearningLog.w(TAG, "Edit without base — treat as new key=$key")
                // Không nest transaction: xử lý trực tiếp như NEW trong cùng block
                if (lines.isEmpty()) {
                    db.inboundMessageDao().insert(
                        InboundMessageEntity(
                            messageKey = key,
                            platform = platform,
                            chatId = chatId,
                            messageId = messageId,
                            customerName = customerName,
                            rawText = rawText,
                            parsedJson = "[]",
                            editCount = 0,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                    reply = "SKIP | edit không khớp cú pháp"
                    return@runInTransaction
                }
                applyLines(lines, ratio, chatId, customerName, key, "INBOUND", now)
                db.inboundMessageDao().insert(
                    InboundMessageEntity(
                        messageKey = key,
                        platform = platform,
                        chatId = chatId,
                        messageId = messageId,
                        customerName = customerName,
                        rawText = rawText,
                        parsedJson = MessageParser.toJson(lines),
                        editCount = 0,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
                TelegramConfig.setSelectedCustomer(app, chatId, customerName)
                reply = "OK | ${MessageParser.summarize(lines)} | msg#$messageId (edit-as-new)"
                return@runInTransaction
            }

            val oldLines = MessageParser.fromJson(existing.parsedJson)
            if (oldLines.isNotEmpty()) {
                applyLines(
                    oldLines.map { ParsedLine(it.code, -it.qty) },
                    ratio,
                    chatId,
                    customerName,
                    key,
                    "REVERSAL",
                    now
                )
            }
            if (lines.isNotEmpty()) {
                applyLines(lines, ratio, chatId, customerName, key, "INBOUND", now)
            }
            db.inboundMessageDao().update(
                existing.copy(
                    customerName = customerName,
                    rawText = rawText,
                    parsedJson = MessageParser.toJson(lines),
                    editCount = existing.editCount + 1,
                    updatedAtEpochMs = now
                )
            )
            TelegramConfig.setSelectedCustomer(app, chatId, customerName)
            val summary = MessageParser.summarize(lines)
            LearningLog.i(
                TAG,
                "EDIT key=$key edit#${existing.editCount + 1} old=${MessageParser.summarize(oldLines)} → $summary"
            )
            reply = "EDIT | $summary | msg#$messageId"
        }
        return reply
    }

    /** Tất toán N vị cho khách đang chọn: ghi SETTLEMENT qtyDelta = -N */
    fun settlePositions(positions: Int): String {
        require(positions in 0..4) { "positions must be 0..4" }
        val chatId = TelegramConfig.getSelectedChatId(app)
        val name = TelegramConfig.getSelectedCustomerName(app)
        if (chatId.isBlank()) {
            LearningLog.w(TAG, "Settle ignored — chưa chọn khách (chưa có tin nào)")
            return "Chưa có khách — hãy để bot nhận 1 tin trước"
        }
        val ratio = TelegramConfig.getPriceRatio(app)
        val now = System.currentTimeMillis()
        val qty = positions.toDouble()
        db.runInTransaction {
            db.ledgerEntryDao().insert(
                LedgerEntryEntity(
                    entryType = "SETTLEMENT",
                    code = "_SETTLE",
                    qtyDelta = -qty,
                    priceRatio = ratio,
                    chatId = chatId,
                    customerName = name.ifBlank { chatId },
                    messageKey = null,
                    note = "Tất toán $positions vị @ratio=$ratio",
                    createdAtEpochMs = now
                )
            )
        }
        LearningLog.i(TAG, "SETTLEMENT chatId=$chatId positions=$positions ratio=$ratio")
        return "Tất toán $positions vị cho $name (chat $chatId)"
    }

    private fun applyLines(
        lines: List<ParsedLine>,
        ratio: Double,
        chatId: String,
        customerName: String,
        messageKey: String,
        entryType: String,
        now: Long
    ) {
        val rows = lines.map { line ->
            LedgerEntryEntity(
                entryType = entryType,
                code = line.code,
                qtyDelta = line.qty,
                priceRatio = ratio,
                chatId = chatId,
                customerName = customerName,
                messageKey = messageKey,
                note = entryType,
                createdAtEpochMs = now
            )
        }
        if (rows.isNotEmpty()) {
            db.ledgerEntryDao().insertAll(rows)
        }
    }

    companion object {
        private const val TAG = "LedgerProcessor"
        const val PLATFORM_TELEGRAM = "telegram"
    }
}
