package com.example.selfxposed

/**
 * 目标应用状态的合并读取。
 *
 * 拦截弹窗页一次要读：功能开关、拦截方式、悬浮球标记、已学规则。
 * 每个都单独跑一次 root/Shizuku 命令会很慢（4~5 次 IPC），
 * 这里用一条命令全部取回并缓存 30 秒。
 */
object TargetFiles {
    data class TargetState(
        val features: Map<String, Boolean> = emptyMap(),
        val pickMode: String = "ball",
        val ballOff: Boolean = false,
        val ruleMode: String = "",
        val ruleTitles: List<String> = emptyList()
    ) {
        fun isOn(feature: String): Boolean = features[feature] == true
    }

    private const val TTL_MS = 30_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TargetState>>()

    fun invalidate(pkg: String) {
        cache.remove(pkg)
    }

    /** 只看缓存，不发起命令；用于页面首帧快速渲染。 */
    fun peek(pkg: String): TargetState? = cache[pkg]?.second

    fun load(pkg: String, force: Boolean = false): TargetState {
        val now = System.currentTimeMillis()
        if (!force) {
            cache[pkg]?.let { (at, state) ->
                if (now - at < TTL_MS) return state
            }
        }
        val state = try {
            val base = "/data/data/$pkg"
            val command = buildString {
                append("D=").append(base).append("; ")
                // 每个 cat 后面补一个 echo：兼容文件结尾没有换行的情况，
                // 否则下一段的标记会粘到这一行末尾，导致解析成 "1@@M" 这种错误值。
                append("echo '@@F'; cat \$D/files/selfxposed_features.conf 2>/dev/null; echo; ")
                append("echo '@@M'; cat \$D/files/selfxposed_pick_mode.conf 2>/dev/null; echo; ")
                append("echo '@@B'; test -f \$D/files/selfxposed_ball_off && echo 1 || echo 0; ")
                append("echo '@@R'; cat \$D/shared_prefs/selfxposed_block.xml 2>/dev/null; echo")
            }
            val raw = RootShell.exec(command, 6_000L)
            // 只有命令真正跑起来（带回 @@F 标记）才缓存；
            // Shizuku 未绑定等失败返回空串，不能缓存，否则 30 秒内都显示未开启。
            if (raw.contains("@@F")) cache[pkg] = now to parse(raw)
            parse(raw)
        } catch (_: Throwable) {
            TargetState()
        }
        return state
    }

    private fun parse(raw: String): TargetState {
        if (raw.isBlank()) return TargetState()
        val sections = linkedMapOf<String, StringBuilder>()
        var current = ""
        raw.lines().forEach { line ->
            when {
                line == "@@F" -> current = "F"
                line == "@@M" -> current = "M"
                line == "@@B" -> current = "B"
                line == "@@R" -> current = "R"
                else -> sections.getOrPut(current) { StringBuilder() }.append(line).append('\n')
            }
        }

        val features = sections["F"]?.toString().orEmpty()
            .lines()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to (line.substring(index + 1).trim() == "1")
            }
            .filter { it.first.isNotEmpty() }
            .toMap()

        val pickMode = sections["M"]?.toString().orEmpty().trim().ifBlank { "ball" }
        val ballOff = sections["B"]?.toString().orEmpty().trim() == "1"

        val ruleXml = sections["R"]?.toString().orEmpty()
        val ruleMode = Regex("<string name=\"mode\">([^<]*)</string>")
            .find(ruleXml)?.groupValues?.get(1).orEmpty()
        val ruleTitles = Regex("<set name=\"titles\">(.*?)</set>", RegexOption.DOT_MATCHES_ALL)
            .find(ruleXml)?.groupValues?.get(1)
            ?.let { block -> Regex("<string>(.*?)</string>").findAll(block).map { it.groupValues[1] }.toList() }
            .orEmpty()

        return TargetState(features, pickMode, ballOff, ruleMode, ruleTitles)
    }
}
