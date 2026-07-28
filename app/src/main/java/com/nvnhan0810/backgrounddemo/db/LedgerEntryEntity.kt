package com.nvnhan0810.backgrounddemo.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Một dòng sổ cái (có thể cộng hoặc trừ).
 * qtyDelta > 0 = nhập; < 0 = đảo (edit) hoặc tất toán.
 */
@Entity(
    tableName = "ledger_entries",
    indices = [
        Index(value = ["code"]),
        Index(value = ["chatId"]),
        Index(value = ["messageKey"])
    ]
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** INBOUND | REVERSAL | SETTLEMENT */
    val entryType: String,
    val code: String,
    val qtyDelta: Double,
    val priceRatio: Double,
    val chatId: String,
    val customerName: String,
    val messageKey: String?,
    val note: String,
    val createdAtEpochMs: Long
)
