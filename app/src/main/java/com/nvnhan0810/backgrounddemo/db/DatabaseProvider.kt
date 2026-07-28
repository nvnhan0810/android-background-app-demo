package com.nvnhan0810.backgrounddemo.db

import android.content.Context
import androidx.room.Room
import com.nvnhan0810.backgrounddemo.LearningLog

/**
 * Singleton giữ 1 kết nối Room/SQLite cho cả app.
 * File DB nằm trong internal storage của app — không cần server, không cần internet.
 */
object DatabaseProvider {

    const val DB_NAME = "background_demo.db"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(context: Context): AppDatabase {
        LearningLog.i(TAG, "Building Room database name=$DB_NAME")
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DB_NAME
        )
            // Learning only: cho phép query trên main thread để chưa cần học coroutine.
            // Production nên dùng Dispatchers.IO / suspend DAO.
            .allowMainThreadQueries()
            .build()
    }

    /**
     * Mở DB + ghi 1 dòng smoke-test, trả về thông tin để hiện trên LearningLog / UI.
     */
    fun openAndSmokeTest(context: Context): DbConnectionInfo {
        val appContext = context.applicationContext
        val file = appContext.getDatabasePath(DB_NAME)
        LearningLog.i(TAG, "Expected DB path: ${file.absolutePath}")

        val db = get(appContext)
        val now = System.currentTimeMillis()
        val dao = db.appMetaDao()

        dao.upsert(
            AppMetaEntity(
                key = KEY_BOOT,
                value = "ok@$now",
                updatedAtEpochMs = now
            )
        )
        val readBack = dao.getByKey(KEY_BOOT)
        val count = dao.count()
        val openHelperPath = db.openHelper.writableDatabase.path

        val info = DbConnectionInfo(
            dbName = DB_NAME,
            absolutePath = openHelperPath ?: file.absolutePath,
            fileExists = file.exists(),
            fileBytes = if (file.exists()) file.length() else 0L,
            metaCount = count,
            bootValue = readBack?.value,
            ok = readBack != null
        )

        if (info.ok) {
            LearningLog.i(
                TAG,
                "SQLite OK path=${info.absolutePath} exists=${info.fileExists} " +
                    "size=${info.fileBytes}B rows=${info.metaCount} boot=${info.bootValue}"
            )
        } else {
            LearningLog.e(TAG, "SQLite smoke-test FAILED — readBack was null")
        }

        return info
    }

    private const val TAG = "DatabaseProvider"
    private const val KEY_BOOT = "db_boot"
}

data class DbConnectionInfo(
    val dbName: String,
    val absolutePath: String,
    val fileExists: Boolean,
    val fileBytes: Long,
    val metaCount: Int,
    val bootValue: String?,
    val ok: Boolean
)
