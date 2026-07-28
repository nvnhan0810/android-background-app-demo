package com.nvnhan0810.backgrounddemo.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * version = 2: thêm inbound_messages + ledger_entries (Telegram ledger demo).
 * Learning: dùng fallbackToDestructiveMigration — cài lại mất data cũ của bảng meta demo.
 */
@Database(
    entities = [
        AppMetaEntity::class,
        InboundMessageEntity::class,
        LedgerEntryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appMetaDao(): AppMetaDao
    abstract fun inboundMessageDao(): InboundMessageDao
    abstract fun ledgerEntryDao(): LedgerEntryDao
}
