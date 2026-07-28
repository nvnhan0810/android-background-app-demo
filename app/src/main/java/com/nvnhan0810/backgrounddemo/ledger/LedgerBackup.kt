package com.nvnhan0810.backgrounddemo.ledger

import android.content.Context
import android.net.Uri
import com.nvnhan0810.backgrounddemo.LearningLog
import com.nvnhan0810.backgrounddemo.db.DatabaseProvider
import com.nvnhan0810.backgrounddemo.db.InboundMessageEntity
import com.nvnhan0810.backgrounddemo.db.LedgerEntryEntity
import com.nvnhan0810.backgrounddemo.telegram.TelegramConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Xuất / nhập backup JSON cục bộ (SAF Uri).
 */
object LedgerBackup {

    fun exportToUri(context: Context, uri: Uri): Boolean {
        return try {
            val db = DatabaseProvider.get(context)
            val messages = db.inboundMessageDao().recent(10_000)
            val ledger = db.ledgerEntryDao().recent(50_000)
            val root = JSONObject()
            root.put("version", 1)
            root.put("exportedAt", System.currentTimeMillis())
            root.put("priceRatio", TelegramConfig.getPriceRatio(context))
            root.put("messages", JSONArray().also { arr ->
                messages.forEach { m ->
                    arr.put(
                        JSONObject()
                            .put("messageKey", m.messageKey)
                            .put("platform", m.platform)
                            .put("chatId", m.chatId)
                            .put("messageId", m.messageId)
                            .put("customerName", m.customerName)
                            .put("rawText", m.rawText)
                            .put("parsedJson", m.parsedJson)
                            .put("editCount", m.editCount)
                            .put("createdAtEpochMs", m.createdAtEpochMs)
                            .put("updatedAtEpochMs", m.updatedAtEpochMs)
                    )
                }
            })
            root.put("ledger", JSONArray().also { arr ->
                ledger.forEach { e ->
                    arr.put(
                        JSONObject()
                            .put("entryType", e.entryType)
                            .put("code", e.code)
                            .put("qtyDelta", e.qtyDelta)
                            .put("priceRatio", e.priceRatio)
                            .put("chatId", e.chatId)
                            .put("customerName", e.customerName)
                            .put("messageKey", e.messageKey)
                            .put("note", e.note)
                            .put("createdAtEpochMs", e.createdAtEpochMs)
                    )
                }
            })

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return false

            LearningLog.i(
                TAG,
                "Export OK messages=${messages.size} ledger=${ledger.size} uri=$uri"
            )
            true
        } catch (t: Throwable) {
            LearningLog.e(TAG, "Export failed", t)
            false
        }
    }

    /** Replace toàn bộ messages + ledger từ file. */
    fun importFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: return false

            val root = JSONObject(text)
            val msgArr = root.optJSONArray("messages") ?: JSONArray()
            val ledArr = root.optJSONArray("ledger") ?: JSONArray()
            if (root.has("priceRatio")) {
                TelegramConfig.setPriceRatio(context, root.getDouble("priceRatio"))
            }

            val messages = mutableListOf<InboundMessageEntity>()
            for (i in 0 until msgArr.length()) {
                val o = msgArr.getJSONObject(i)
                messages += InboundMessageEntity(
                    messageKey = o.getString("messageKey"),
                    platform = o.getString("platform"),
                    chatId = o.getString("chatId"),
                    messageId = o.getString("messageId"),
                    customerName = o.optString("customerName"),
                    rawText = o.optString("rawText"),
                    parsedJson = o.optString("parsedJson", "[]"),
                    editCount = o.optInt("editCount", 0),
                    createdAtEpochMs = o.optLong("createdAtEpochMs"),
                    updatedAtEpochMs = o.optLong("updatedAtEpochMs")
                )
            }
            val entries = mutableListOf<LedgerEntryEntity>()
            for (i in 0 until ledArr.length()) {
                val o = ledArr.getJSONObject(i)
                entries += LedgerEntryEntity(
                    entryType = o.getString("entryType"),
                    code = o.getString("code"),
                    qtyDelta = o.getDouble("qtyDelta"),
                    priceRatio = o.optDouble("priceRatio", 1.0),
                    chatId = o.getString("chatId"),
                    customerName = o.optString("customerName"),
                    messageKey = if (o.isNull("messageKey")) null else o.optString("messageKey"),
                    note = o.optString("note"),
                    createdAtEpochMs = o.optLong("createdAtEpochMs")
                )
            }

            val db = DatabaseProvider.get(context)
            db.runInTransaction {
                db.inboundMessageDao().deleteAll()
                db.ledgerEntryDao().deleteAll()
                if (messages.isNotEmpty()) {
                    db.inboundMessageDao().insertAll(messages)
                }
                if (entries.isNotEmpty()) {
                    // insert từng dòng vì insertAll ABORT trên entity có auto id = 0 vẫn OK
                    entries.forEach { db.ledgerEntryDao().insert(it) }
                }
            }
            LearningLog.i(TAG, "Import OK messages=${messages.size} ledger=${entries.size}")
            true
        } catch (t: Throwable) {
            LearningLog.e(TAG, "Import failed", t)
            false
        }
    }

    private const val TAG = "LedgerBackup"
}
