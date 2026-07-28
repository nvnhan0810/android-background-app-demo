package com.nvnhan0810.backgrounddemo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface InboundMessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(row: InboundMessageEntity): Long

    @Update
    fun update(row: InboundMessageEntity)

    @Query("SELECT * FROM inbound_messages WHERE messageKey = :key LIMIT 1")
    fun findByKey(key: String): InboundMessageEntity?

    @Query("SELECT * FROM inbound_messages ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 100): List<InboundMessageEntity>

    @Query("SELECT COUNT(*) FROM inbound_messages")
    fun count(): Int

    @Query("DELETE FROM inbound_messages")
    fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rows: List<InboundMessageEntity>)
}
