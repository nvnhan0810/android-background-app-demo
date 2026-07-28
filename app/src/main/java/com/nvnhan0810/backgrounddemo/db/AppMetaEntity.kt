package com.nvnhan0810.backgrounddemo.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Bảng SQLite tối giản để smoke-test kết nối DB.
 * Entity = 1 hàng trong bảng; Room sẽ tạo SQL CREATE TABLE giúp bạn.
 *
 * Sau này có thể thêm Entity khác (ví dụ Note, Task…) — bảng này vẫn giữ để meta/local settings.
 */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAtEpochMs: Long
)
