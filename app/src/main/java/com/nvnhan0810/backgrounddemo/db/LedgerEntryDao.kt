package com.nvnhan0810.backgrounddemo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

data class CodeTotal(
    val code: String,
    val totalQty: Double
)

data class CustomerBalance(
    val chatId: String,
    val customerName: String,
    val balanceQty: Double
)

@Dao
interface LedgerEntryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(row: LedgerEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAll(rows: List<LedgerEntryEntity>)

    @Query(
        """
        SELECT code AS code, SUM(qtyDelta) AS totalQty
        FROM ledger_entries
        WHERE code != '' AND code != '_SETTLE'
        GROUP BY code
        HAVING ABS(SUM(qtyDelta)) > 0.000001
        ORDER BY code ASC
        """
    )
    fun totalsByCode(): List<CodeTotal>

    @Query(
        """
        SELECT chatId AS chatId,
               MAX(customerName) AS customerName,
               SUM(qtyDelta) AS balanceQty
        FROM ledger_entries
        GROUP BY chatId
        HAVING ABS(SUM(qtyDelta)) > 0.000001
        ORDER BY customerName ASC
        """
    )
    fun balancesByCustomer(): List<CustomerBalance>

    @Query("SELECT COALESCE(SUM(qtyDelta), 0) FROM ledger_entries")
    fun netQty(): Double

    @Query(
        """
        SELECT COALESCE(SUM(qtyDelta), 0) FROM ledger_entries
        WHERE entryType = 'INBOUND'
        """
    )
    fun inboundQty(): Double

    @Query(
        """
        SELECT COALESCE(SUM(ABS(qtyDelta)), 0) FROM ledger_entries
        WHERE entryType = 'SETTLEMENT'
        """
    )
    fun settledQtyAbs(): Double

    @Query("SELECT * FROM ledger_entries ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun recent(limit: Int = 200): List<LedgerEntryEntity>

    @Query("SELECT COUNT(*) FROM ledger_entries")
    fun count(): Int

    @Query("DELETE FROM ledger_entries")
    fun deleteAll()
}
