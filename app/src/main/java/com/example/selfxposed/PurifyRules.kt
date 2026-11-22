package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * 净化的规则存储与解析。
 *
 * 数据落点（沿用 AGENTS.md §3：hook 要读的数据一律放目标应用自己的目录）：
 * - 规则：`files/selfxposed_purify.conf`，每行一条，`|` 分隔
 * - 命中数：`files/selfxposed_purify_hits.conf`，形如 `规则id=次数`
 * - 跳过原因：`files/selfxposed_purify_skipped.conf`，形如 `规则id=占屏 92%，已跳过`
 *
 * hook 与目标应用同进程，直接读写这些文件；模块 UI 通过 root / Shizuku 读写。
 * 规则、命中数、跳过原因分成三个文件：这三个由不同进程在不同时机写，
 * 合在一起会互相覆盖（例如 UI 写规则时把 hook 刚记下的命中数抹掉）。
 */
object PurifyRules {
    const val CONF_FILE = "selfxposed_purify.conf"
    const val HITS_FILE = "selfxposed_purify_hits.conf"
    const val SKIPPED_FILE = "selfxposed_purify_skipped.conf"

    /** 控件小图存放目录（在目标应用自己的 files 下），文件名就是规则编号.png */
    const val SHOT_DIR = "selfxposed_purify_shots"

    /** 匹配方式。 */
    const val TYPE_ID = "id"
    const val TYPE_TEXT = "text"
    const val TYPE_DESC = "desc"
    const val TYPE_CLASS = "class"
    /** 控件 ID 包含关键字（用于跨页面 ad_container 这类命名） */
    const val TYPE_ID_CONTAINS = "id_contains"

    /** 动作。 */
    const val ACTION_HIDE = "hide"
    const val ACTION_INVISIBLE = "invisible"
    const val ACTION_REMOVE = "remove"
    const val ACTION_TEXT = "text"
    const val ACTION_SIZE = "size"

    /** 单条规则的字段顺序：id|on|hits|activity|type|value|action|arg */
    private const val SEP = '|'
    private const val MAX_RULES = 200

    data class Rule(
        val id: String,
        val on: Boolean = true,
        val hits: Int = 0,
        /** 限定界面（Activity 简名）；空 = 该应用的所有界面都生效。 */
        val activity: String = "",
        val type: String = TYPE_ID,
        val value: String = "",
        val action: String = ACTION_HIDE,
        /** ACTION_TEXT 的替换文字，或 ACTION_SIZE 的 "宽x高"。 */
        val arg: String = "",
        /**
         * 运行时状态：本条规则最近一次被黑屏保护跳过时的说明（空 = 没有被跳过）。
         * 来自 SKIPPED_FILE，由 hook 写入；不属于规则本身，所以不参与 [toLine]。
         */
        val skipped: String = ""
    ) {
        fun toLine(): String = listOf(
            escape(id),
            if (on) "1" else "0",
            hits.toString(),
            escape(activity),
            type,
            escape(value),
            action,
            escape(arg)
        ).joinToString(SEP.toString())

        /** 规则列表里每条的说明文字。 */
        fun summary(): String = buildString {
            append(actionLabel(action))
            append(" · ")
            append(typeLabel(type))
            append("：")
            append(value.ifBlank { "（空）" })
            if (arg.isNotBlank()) {
                append(" → ")
                append(arg)
            }
            if (activity.isNotBlank()) {
                append("（仅 ")
                append(activity)
                append("）")
            }
        }
    }

    fun newId(): String = "r" + System.currentTimeMillis().toString(36)

    // ---- 解析 ----

    /**
     * 解析规则文本。
     * @param hits 命中数（取自 HITS_FILE）
     * @param skipped 黑屏保护跳过原因（取自 SKIPPED_FILE）
     */
    fun parse(
        raw: String,
        hits: Map<String, Int> = emptyMap(),
        skipped: Map<String, String> = emptyMap()
    ): List<Rule> {
        if (raw.isBlank()) return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> parseLine(line, hits, skipped) }
            .take(MAX_RULES)
    }

    private fun parseLine(
        line: String,
        hits: Map<String, Int>,
        skipped: Map<String, String>
    ): Rule? {
        val parts = splitLine(line)
        if (parts.size < 7) return null
        val id = parts[0].ifBlank { return null }
        val type = parts[4].ifBlank { TYPE_ID }
        val action = parts[6].ifBlank { ACTION_HIDE }
        return Rule(
            id = id,
            on = parts[1] == "1",
            hits = parts[2].toIntOrNull() ?: hits[id] ?: 0,
            activity = parts[3],
            type = type,
            value = parts[5],
            action = action,
            arg = parts.getOrNull(7).orEmpty(),
            skipped = skipped[id].orEmpty()
        )
    }

    /** 跳过原因文本：每行 `规则id=说明`。 */
    fun parseSkipped(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) {
                null
            } else {
                val id = line.substring(0, index).trim()
                val reason = line.substring(index + 1).trim()
                if (id.isEmpty() || reason.isEmpty()) null else id to reason
            }
        }.toMap()
    }

    /** 命中数文本：每行 `id=次数`。 */
    fun parseHits(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) {
                null
            } else {
                val id = line.substring(0, index).trim()
                val count = line.substring(index + 1).trim().toIntOrNull() ?: 0
                if (id.isEmpty()) null else id to count
            }
        }.toMap()
    }

    private fun splitLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val current = StringBuilder()
        var index = 0
        while (index < line.length) {
            val c = line[index]
            if (c == '\\' && index + 1 < line.length) {
                current.append(c).append(line[index + 1])
                index += 2
                continue
            }
            if (c == SEP) {
                out.add(unescape(current.toString()))
                current.setLength(0)
                index++
                continue
            }
            current.append(c)
            index++
        }
        out.add(unescape(current.toString()))
        return out
    }

    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                SEP -> append("\\").append(SEP)
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n' -> append('\n')
                    '\\' -> append('\\')
                    SEP -> append(SEP)
                    else -> append(next)
                }
                index += 2
            } else {
                append(c)
                index++
            }
        }
    }

    // ---- hook 侧（目标应用进程内，直接读写自己目录） ----

    fun loadLocal(context: Context): List<Rule> = try {
        val file = File(context.filesDir, CONF_FILE)
        if (!file.exists()) {
            emptyList()
        } else {
            parse(file.readText(), loadLocalHits(context), loadLocalSkipped(context))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun loadLocalHits(context: Context): Map<String, Int> = try {
        val file = File(context.filesDir, HITS_FILE)
        if (!file.exists()) emptyMap() else parseHits(file.readText())
    } catch (_: Throwable) {
        emptyMap()
    }

    fun saveLocalHits(context: Context, hits: Map<String, Int>) {
        try {
            val file = File(context.filesDir, HITS_FILE)
            file.writeText(hits.entries.joinToString("\n") { it.key + "=" + it.value })
        } catch (_: Throwable) {
        }
    }

    /** 黑屏保护跳过原因（hook 侧读，用来在应用成功后把旧标记清掉）。 */
    fun loadLocalSkipped(context: Context): Map<String, String> = try {
        val file = File(context.filesDir, SKIPPED_FILE)
        if (!file.exists()) emptyMap() else parseSkipped(file.readText())
    } catch (_: Throwable) {
        emptyMap()
    }

    fun saveLocalSkipped(context: Context, skipped: Map<String, String>) {
        try {
            val file = File(context.filesDir, SKIPPED_FILE)
            if (skipped.isEmpty()) {
                file.delete()
            } else {
                file.writeText(skipped.entries.joinToString("\n") { it.key + "=" + it.value })
            }
        } catch (_: Throwable) {
        }
    }

    // ---- UI 侧（模块进程，走 root / Shizuku） ----

    fun readRemote(pkg: String): List<Rule> = try {
        val base = "/data/data/$pkg/files"
        // 一条命令取回三个文件，避免多次 IPC（页面上一次刷新要读好几项）
        val raw = RootShell.exec(
            "cat $base/$CONF_FILE 2>/dev/null; echo '@@H'; cat $base/$HITS_FILE 2>/dev/null; " +
                "echo '@@K'; cat $base/$SKIPPED_FILE 2>/dev/null; echo",
            6_000L
        )
        // 规则文件内容在第一个 @@H 之前；用 sectionOf 从 @@H 开始取会把后面的
        // 命中数、跳过原因一起当成规则文本，解析出错误的规则。
        val conf = raw.substringBefore("@@H")
        val hits = sectionOf(raw, "@@H", "@@K")
        val skipped = sectionOf(raw, "@@K", null)
        parse(conf, parseHits(hits), parseSkipped(skipped))
    } catch (_: Throwable) {
        emptyList()
    }

    /** 取两个标记之间的内容；end 为 null 表示取到结尾。 */
    private fun sectionOf(raw: String, start: String, end: String?): String {
        val from = raw.indexOf(start)
        if (from < 0) return ""
        val begin = from + start.length
        val to = if (end == null) -1 else raw.indexOf(end, begin)
        return if (to < 0) raw.substring(begin) else raw.substring(begin, to)
    }

    fun writeRemote(pkg: String, rules: List<Rule>): Boolean = try {
        val base = "/data/data/$pkg/files"
        val content = rules.take(MAX_RULES).joinToString("\n") { it.toLine() }
        // printf 的 %s 不解析转义，所以只需为 shell 单引号转义
        val escaped = content.replace("'", "'\\''")
        val out = RootShell.exec(
            "mkdir -p $base; printf '%s\\n' '$escaped' > $base/$CONF_FILE; " +
                "chown $(stat -c %u:%g /data/data/$pkg) $base/$CONF_FILE; " +
                "chmod 660 $base/$CONF_FILE; echo ok",
            6_000L
        )
        out.contains("ok")
    } catch (_: Throwable) {
        false
    }

    /** 清空命中数（规则保留）。 */
    fun clearHitsRemote(pkg: String): Boolean = try {
        val out = RootShell.exec("rm -f /data/data/$pkg/files/$HITS_FILE 2>/dev/null; echo ok")
        out.contains("ok")
    } catch (_: Throwable) {
        false
    }

    // ---- 相同规则只留最新一条 ----

    /**
     * 去掉重复的规则：匹配方式、匹配值、生效界面、动作、参数全都一样的，只保留最后一条。
     * 新加的规则都在列表末尾，所以"后面那条"就是最新的。
     */
    fun dedupe(rules: List<Rule>): List<Rule> {
        val seen = HashSet<String>()
        val out = ArrayList<Rule>(rules.size)
        for (index in rules.indices.reversed()) {
            val rule = rules[index]
            val key = listOf(rule.activity, rule.type, rule.value, rule.action, rule.arg)
                .joinToString("\u0001")
            if (seen.add(key)) out.add(rule)
        }
        out.reverse()
        return out
    }

    // ---- 控件小图 ----

    private fun shotDirRemote(pkg: String) = "/data/data/$pkg/files/$SHOT_DIR"

    /**
     * 把目标应用里的所有小图一次性拷到模块自己的缓存目录。
     *
     * 为什么要拷贝：目标应用是私有目录，模块自己的进程读不到；
     * 用 root/Shizuku 拷出来一份，模块就能正常显示。用一条命令拷整个目录，
     * 比每张图单独跑一次命令快得多。
     */
    fun syncShots(pkg: String, cacheDir: File): Boolean = try {
        val local = File(cacheDir, "purify_shots")
        // 先清空本地旧图，避免删掉的规则还留着图
        local.deleteRecursively()
        local.mkdirs()
        val command = buildString {
            append("SRC=").append(shotDirRemote(pkg)).append("; ")
            append("DST='").append(local.absolutePath).append("'; ")
            append("if [ -d \"\$SRC\" ]; then cp -f \"\$SRC\"/*.png \"\$DST\"/ 2>/dev/null; ").append("fi; ")
            append("chmod 644 \"\$DST\"/*.png 2>/dev/null; echo ok")
        }
        val out = RootShell.exec(command, 8_000L)
        out.contains("ok")
    } catch (_: Throwable) {
        false
    }

    /** 模块缓存目录里的本地图片文件（可能不存在）。 */
    fun localShot(cacheDir: File, ruleId: String): File = File(File(cacheDir, "purify_shots"), "$ruleId.png")

    /** 删掉某条规则的图。 */
    fun deleteShotRemote(pkg: String, ruleId: String): Boolean = try {
        val out = RootShell.exec("rm -f '${shotDirRemote(pkg)}/$ruleId.png' 2>/dev/null; echo ok")
        out.contains("ok")
    } catch (_: Throwable) {
        false
    }

    /** 清掉不属于任何现存规则的图片，避免越攒越多。 */
    fun pruneShotsRemote(pkg: String, aliveIds: Collection<String>): Boolean = try {
        val dir = shotDirRemote(pkg)
        val command = buildString {
            append("D='").append(dir).append("'; ")
            append("[ -d \"\$D\" ] || { echo ok; exit 0; }; ")
            // 先把现存规则编号拼成白名单，再把不在名单里的图删掉
            append("KEEP='").append(aliveIds.joinToString(" ")).append("'; ")
            append("for f in \"\$D\"/*.png; do ")
            append("[ -e \"\$f\" ] || continue; ")
            append("n=\$(basename \"\$f\" .png); ")
            append("case \" \$KEEP \" in *\" \$n \"*) ;; *) rm -f \"\$f\";; esac; ")
            append("done; echo ok")
        }
        val out = RootShell.exec(command, 8_000L)
        out.contains("ok")
    } catch (_: Throwable) {
        false
    }

    // ---- 导入 / 导出 ----

    fun exportText(rules: List<Rule>): String = buildString {
        append("# 自用模块 · 净化规则\n")
        append("# 每行一条：id|开关|命中数|界面|匹配方式|匹配值|动作|参数\n")
        append("# 匹配方式：id / text / desc / class / id_contains；动作：hide / invisible / remove / text / size\n")
        rules.forEach { rule ->
            append(rule.toLine())
            append('\n')
        }
    }

    // ---- 文案 ----

    fun typeLabel(type: String): String = when (type) {
        TYPE_ID -> "控件 ID"
        TYPE_TEXT -> "文字"
        TYPE_DESC -> "描述"
        TYPE_CLASS -> "类名"
        TYPE_ID_CONTAINS -> "控件 ID 包含"
        else -> type
    }

    fun actionLabel(action: String): String = when (action) {
        ACTION_HIDE -> "隐藏"
        ACTION_INVISIBLE -> "占位不可见"
        ACTION_REMOVE -> "移除"
        ACTION_TEXT -> "改文字"
        ACTION_SIZE -> "改尺寸"
        else -> action
    }

    /** 系统/框架通用控件类：用它们做「类名」规则会误伤整页，禁止保存。 */
    private val GENERIC_CLASS_PREFIXES = listOf(
        "android.widget.",
        "android.view.",
        "android.app.",
        "android.webkit.",
        "androidx.",
        "android.support.",
        "com.google.android.material."
    )

    fun isGenericClassName(name: String): Boolean =
        name.isBlank() || GENERIC_CLASS_PREFIXES.any { name.startsWith(it) }

    fun matches(rule: Rule, activityName: String, idName: String, text: String, desc: String, className: String): Boolean {
        if (!rule.on) return false
        if (rule.activity.isNotBlank() && rule.activity != activityName) return false
        val key = rule.value
        if (key.isEmpty()) return false
        return when (rule.type) {
            TYPE_ID -> idName.equals(key, ignoreCase = true)
            TYPE_TEXT -> text.isNotEmpty() && text.equals(key, ignoreCase = true)
            TYPE_DESC -> desc.isNotEmpty() && desc.equals(key, ignoreCase = true)
            TYPE_CLASS -> !isGenericClassName(key) && className.isNotEmpty() &&
                className.equals(key, ignoreCase = true)
            TYPE_ID_CONTAINS -> idName.isNotEmpty() && idName.contains(key, ignoreCase = true)
            else -> false
        }
    }
}
