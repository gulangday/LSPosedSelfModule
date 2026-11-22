package com.example.selfxposed

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 只读查询 LSPosed 的作用域数据库，判断目标应用是否被勾选。
 *
 * 需要 Root / Shizuku：把 modules_config.db 连同 -wal/-shm 复制到模块缓存后
 * 用 SQLite 只读打开。查询失败（无权限、版本结构变化、数据库损坏）返回 null，
 * 调用方应退回「注入标记」判断，不能当成「未勾选」。
 */
object LsposedScope {
    private const val DB_DIR = "/data/adb/lspd/config"
    private const val DB_NAME = "modules_config.db"
    private const val CACHE_TTL_MS = 10_000L
    private const val TAG = "SelfXposedScope"

    private data class Entry(val at: Long, val result: Boolean?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun isInScope(context: Context, pkg: String, force: Boolean = false): Boolean? {
        val now = System.currentTimeMillis()
        if (!force) {
            cache[pkg]?.let { (at, result) ->
                if (now - at < CACHE_TTL_MS) return result
            }
        }
        val result = try {
            query(context, pkg)
        } catch (_: Throwable) {
            null
        }
        android.util.Log.i(TAG, "pkg=$pkg inScope=$result")
        cache[pkg] = Entry(now, result)
        return result
    }

    /** 首帧使用：只读缓存，不发起 root 查询。 */
    fun cached(pkg: String): Boolean? {
        val entry = cache[pkg] ?: return null
        return if (System.currentTimeMillis() - entry.at < CACHE_TTL_MS) entry.result else null
    }

    fun invalidate(pkg: String) {
        cache.remove(pkg)
    }

    private fun query(context: Context, pkg: String): Boolean? {
        val dir = File(context.cacheDir, "lsposed_scope").apply { mkdirs() }
        val dbFile = File(dir, DB_NAME)
        val walFile = File(dir, "$DB_NAME-wal")
        val shmFile = File(dir, "$DB_NAME-shm")
        listOf(dbFile, walFile, shmFile).forEach { it.delete() }

        val command = buildString {
            append("OWNER=\$(stat -c %u:%g '").append(dir.absolutePath).append("'); ")
            append("cp -f ").append(DB_DIR).append("/").append(DB_NAME)
            append(" '").append(dbFile.absolutePath).append("' 2>/dev/null; ")
            append("cp -f ").append(DB_DIR).append("/").append(DB_NAME).append("-wal")
            append(" '").append(walFile.absolutePath).append("' 2>/dev/null; ")
            append("cp -f ").append(DB_DIR).append("/").append(DB_NAME).append("-shm")
            append(" '").append(shmFile.absolutePath).append("' 2>/dev/null; ")
            append("chown \$OWNER '").append(dbFile.absolutePath).append("' ")
            append("'").append(walFile.absolutePath).append("' ")
            append("'").append(shmFile.absolutePath).append("' 2>/dev/null; ")
            append("chmod 600 '").append(dbFile.absolutePath).append("' ")
            append("'").append(walFile.absolutePath).append("' ")
            append("'").append(shmFile.absolutePath).append("' 2>/dev/null; echo ok")
        }
        val out = RootShell.exec(command, 8_000L)
        if (!out.contains("ok") || !dbFile.exists()) {
            android.util.Log.w(TAG, "copy failed pkg=$pkg out=${out.take(120)}")
            listOf(dbFile, walFile, shmFile).forEach { it.delete() }
            return null
        }

        val db = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "open failed pkg=$pkg: ${t.javaClass.simpleName}: ${t.message}")
            listOf(dbFile, walFile, shmFile).forEach { it.delete() }
            return null
        }
        return try {
            val scopeColumns = columns(db, "scope")
            val modulesColumns = columns(db, "modules")
            if (scopeColumns.isEmpty() || modulesColumns.isEmpty()) {
                android.util.Log.w(TAG, "missing tables scope=$scopeColumns modules=$modulesColumns")
                return null
            }

            val moduleColumn = if ("module_pkg_name" in modulesColumns) "module_pkg_name"
            else if ("mid" in modulesColumns) "mid"
            else return null
            val scopeModuleColumn = if ("module_pkg_name" in scopeColumns) "module_pkg_name"
            else if ("mid" in scopeColumns) "mid"
            else return null

            val sql = "SELECT 1 FROM scope s JOIN modules m ON m.$moduleColumn = s.$scopeModuleColumn " +
                "WHERE m.$moduleColumn = ? AND s.app_pkg_name = ? LIMIT 1"
            db.rawQuery(sql, arrayOf(ConfigClient.MODULE_PACKAGE, pkg)).use { cursor ->
                cursor.moveToFirst()
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "query failed pkg=$pkg: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            try {
                db.close()
            } catch (_: Throwable) {
            }
            listOf(dbFile, walFile, shmFile).forEach { it.delete() }
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> = try {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return emptySet()
            val out = HashSet<String>()
            while (cursor.moveToNext()) out.add(cursor.getString(nameIndex))
            out
        }
    } catch (_: Throwable) {
        emptySet()
    }
}
