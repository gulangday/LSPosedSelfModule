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
            val db = openCopy(context) ?: return null
            try {
                scopePackages(db)?.contains(pkg)
            } finally {
                closeAndDelete(db, context)
            }
        } catch (_: Throwable) {
            null
        }
        android.util.Log.i(TAG, "pkg=$pkg inScope=$result")
        cache[pkg] = Entry(now, result)
        return result
    }

    /**
     * 批量查询：整轮只复制一次数据库、只跑一条 SQL，避免逐包复制整个 db。
     * 表结构不认识时返回 null，调用方回退到逐包查询。
     */
    fun isInScopeBatch(context: Context, pkgs: List<String>, force: Boolean = false): Map<String, Boolean>? {
        if (pkgs.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        if (!force) {
            val allCached = pkgs.all { pkg -> cache[pkg]?.let { now - it.at < CACHE_TTL_MS } == true }
            if (allCached) {
                return pkgs.associateWith { cache[it]?.result == true }
            }
        }
        val result = try {
            val db = openCopy(context) ?: return null
            try {
                val inScope = scopePackages(db) ?: return null
                val map = HashMap<String, Boolean>()
                pkgs.forEach { pkg ->
                    val value = pkg in inScope
                    cache[pkg] = Entry(now, value)
                    map[pkg] = value
                }
                map
            } finally {
                closeAndDelete(db, context)
            }
        } catch (_: Throwable) {
            null
        }
        if (result != null) android.util.Log.i(TAG, "batch=${pkgs.size} inScope=${result.count { it.value }}")
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

    private fun copyDir(context: Context) = File(context.cacheDir, "lsposed_scope")

    private fun copyFiles(context: Context): List<File> {
        val dir = copyDir(context).apply { mkdirs() }
        val dbFile = File(dir, DB_NAME)
        return listOf(dbFile, File(dir, "$DB_NAME-wal"), File(dir, "$DB_NAME-shm"))
    }

    /** 把模块数据库连同 wal/shm 复制到缓存目录并打开只读副本；失败返回 null。 */
    private fun openCopy(context: Context): SQLiteDatabase? {
        val files = copyFiles(context).also { list -> list.forEach { it.delete() } }
        val dbFile = files[0]

        val command = buildString {
            append("OWNER=\$(stat -c %u:%g '").append(copyDir(context).absolutePath).append("'); ")
            append("cp -f ").append(DB_DIR).append("/").append(DB_NAME)
            append(" '").append(files[0].absolutePath).append("' 2>/dev/null; ")
            append("cp -f ").append(DB_DIR).append("/").append(DB_NAME).append("-wal")
            append(" '").append(files[1].absolutePath).append("' 2>/dev/null; ")
            append("cp -f ").append(DB_DIR).append("/").append(DB_NAME).append("-shm")
            append(" '").append(files[2].absolutePath).append("' 2>/dev/null; ")
            append("chown \$OWNER '").append(files[0].absolutePath).append("' ")
            append("'").append(files[1].absolutePath).append("' ")
            append("'").append(files[2].absolutePath).append("' 2>/dev/null; ")
            append("chmod 600 '").append(files[0].absolutePath).append("' ")
            append("'").append(files[1].absolutePath).append("' ")
            append("'").append(files[2].absolutePath).append("' 2>/dev/null; echo ok")
        }
        val out = RootShell.exec(command, 8_000L)
        if (!out.contains("ok") || !dbFile.exists()) {
            android.util.Log.w(TAG, "copy failed out=${out.take(120)}")
            files.forEach { it.delete() }
            return null
        }

        return try {
            // 读写模式打开：WAL 恢复需要能写缓存副本
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "open failed: ${t.javaClass.simpleName}: ${t.message}")
            files.forEach { it.delete() }
            null
        }
    }

    private fun closeAndDelete(db: SQLiteDatabase, context: Context) {
        try {
            db.close()
        } catch (_: Throwable) {
        }
        copyFiles(context).forEach { it.delete() }
    }

    /** 本模块在 LSPosed 里勾选的全部包名；表结构不认识时返回 null。 */
    private fun scopePackages(db: SQLiteDatabase): Set<String>? {
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

        val sql = "SELECT s.app_pkg_name FROM scope s JOIN modules m ON m.$moduleColumn = s.$scopeModuleColumn " +
            "WHERE m.$moduleColumn = ?"
        val out = HashSet<String>()
        try {
            db.rawQuery(sql, arrayOf(ConfigClient.MODULE_PACKAGE)).use { cursor ->
                val index = cursor.getColumnIndex("app_pkg_name")
                if (index < 0) return null
                while (cursor.moveToNext()) {
                    cursor.getString(index)?.let { out.add(it) }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "query failed: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
        return out
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
