package com.nvnhan0810.backgrounddemo.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * AppDatabase = “cửa vào” toàn bộ SQLite của app.
 * version tăng khi đổi schema (thêm cột/bảng) — sau này sẽ học Migration.
 */
@Database(
    entities = [AppMetaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appMetaDao(): AppMetaDao
}
