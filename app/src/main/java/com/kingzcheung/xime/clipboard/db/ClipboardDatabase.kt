package com.kingzcheung.xime.clipboard.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Database(
    entities = [ClipboardEntry::class],
    version = 5,
    exportSchema = false
)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        private const val DATABASE_NAME = "clipboard.db"

        /** v1 → v2：新增 consumed 列（候选栏"已消费"剪贴板项过滤）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN consumed INTEGER NOT NULL DEFAULT 0"
                ).step()
            }
        }

        /** v2 → v3：新增 code 列（快捷发送触发编码）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN code TEXT NOT NULL DEFAULT ''"
                ).step()
            }
        }

        /**
         * v3 → v4：图片剪贴板支持——新增类型/图片元数据列与 imageHash 索引。
         * 既有行按 DEFAULT 落为文本条目，行为不变。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                listOf(
                    "ALTER TABLE clipboard_entries ADD COLUMN type TEXT NOT NULL DEFAULT 'text'",
                    "ALTER TABLE clipboard_entries ADD COLUMN imagePath TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE clipboard_entries ADD COLUMN imageHash TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE clipboard_entries ADD COLUMN mimeType TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE clipboard_entries ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE clipboard_entries ADD COLUMN width INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE clipboard_entries ADD COLUMN height INTEGER NOT NULL DEFAULT 0",
                ).forEach { connection.prepare(it).step() }
                // 实体声明了 imageHash 索引：Room 会校验期望 schema，缺失索引会抛
                // "Migration didn't properly handle"，必须显式创建。
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_entries_imageHash " +
                        "ON clipboard_entries (imageHash)"
                ).step()
            }
        }

        /**
         * v4 → v5：补三个复合索引。
         *
         * 此前只有 text / imageHash 两个单列索引，而面板与候选栏的核心查询是
         * `WHERE isQuickSend = 0 ORDER BY timestamp DESC`、`WHERE isPinned = 0 ORDER BY timestamp`、
         * `WHERE isQuickSend = 0 AND type = 'image'`——全部退化为全表扫描 + 临时排序。
         * 1000 条条目时每次复制/每次打开面板都要付这个代价。
         *
         * Room 会校验期望 schema：实体声明了索引但迁移没建，会抛
         * "Migration didn't properly handle"，所以这里必须显式 CREATE INDEX。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                listOf(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_entries_isQuickSend_timestamp " +
                        "ON clipboard_entries (isQuickSend, timestamp)",
                    "CREATE INDEX IF NOT EXISTS index_clipboard_entries_isPinned_timestamp " +
                        "ON clipboard_entries (isPinned, timestamp)",
                    "CREATE INDEX IF NOT EXISTS index_clipboard_entries_isQuickSend_type " +
                        "ON clipboard_entries (isQuickSend, type)",
                ).forEach { connection.prepare(it).step() }
            }
        }

        @Volatile
        private var instance: ClipboardDatabase? = null

        /**
         * 数据库 IO 作用域（进程级单例）。
         *
         * 旧实现 `fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)` 每次调用都
         * 新建一个**永不取消**的作用域：ClipboardManager 持有一个、其他调用点各持有一个，
         * Job 泄漏且无法统一取消（服务销毁后仍在跑 DB 写入与配额收敛）。
         */
        private val ioScope: CoroutineScope by lazy {
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        fun getInstance(context: Context): ClipboardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder<ClipboardDatabase>(
                    context.applicationContext,
                    DATABASE_NAME
                )
                    .setDriver(AndroidSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
        }

        /** 进程级数据库 IO 作用域（单例，不再每次新建）。 */
        fun scope(): CoroutineScope = ioScope
    }
}
