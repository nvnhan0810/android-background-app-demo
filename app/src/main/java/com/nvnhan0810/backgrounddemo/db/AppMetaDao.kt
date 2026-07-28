package com.nvnhan0810.backgrounddemo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO (Data Access Object) = nơi khai báo lệnh đọc/ghi bảng.
 * Room sinh code SQL thật từ các hàm này lúc build (nhờ KSP).
 */
@Dao
interface AppMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(meta: AppMetaEntity)

    @Query("SELECT * FROM app_meta WHERE `key` = :key LIMIT 1")
    fun getByKey(key: String): AppMetaEntity?

    @Query("SELECT COUNT(*) FROM app_meta")
    fun count(): Int
}
