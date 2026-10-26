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
        val ruleTitles: List<String> = emptyList(),
        /** 版本伪装：伪装版本名 / 版本号（0 表示没设置） */
        val fakeName: String = "",
        val fakeCode: Int = 0,
        /** 去广告：用户自定义的 SDK 前缀（空 = 用内置默认） */
        val adSdkLines: List<String> = emptyList(),
        /** 去广告：用户自定义的广告域名（空 = 用内置默认） */
        val adUrlLines: List<String> = emptyList()
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

    /** 只读缓存，但超过 maxAgeMs 视为没有（用于主页判断是否需要后台补读）。 */
    fun peekFresh(pkg: String, maxAgeMs: Long): TargetState? {
        val entry = cache[pkg] ?: return null
        return if (System.currentTimeMillis() - entry.first <= maxAgeMs) entry.second else null
    }

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
                append("echo '@@U'; cat \$D/files/selfxposed_update_block.conf 2>/dev/null; echo; ")
                append("echo '@@A'; cat \$D/files/selfxposed_ad_sdk.conf 2>/dev/null; echo; ")
                append("echo '@@W'; cat \$D/files/selfxposed_ad_urls.conf 2>/dev/null; echo; ")
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
                line == "@@U" -> current = "U"
                line == "@@A" -> current = "A"
                line == "@@W" -> current = "W"
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

        // 版本伪装配置：enabled=1 / fakeName=9.9.9 / fakeCode=999999
        val updateConf = sections["U"]?.toString().orEmpty().lines()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
        val fakeName = updateConf["fakeName"].orEmpty()
        val fakeCode = updateConf["fakeCode"]?.toIntOrNull() ?: 0

        // 去广告：自定义 SDK 前缀（每行一个）
        val adSdkLines = sections["A"]?.toString().orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val adUrlLines = sections["W"]?.toString().orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val ruleXml = sections["R"]?.toString().orEmpty()
        val ruleMode = Regex("<string name=\"mode\">([^<]*)</string>")
            .find(ruleXml)?.groupValues?.get(1).orEmpty()
        val ruleTitles = Regex("<set name=\"titles\">(.*?)</set>", RegexOption.DOT_MATCHES_ALL)
            .find(ruleXml)?.groupValues?.get(1)
            ?.let { block -> Regex("<string>(.*?)</string>").findAll(block).map { it.groupValues[1] }.toList() }
            .orEmpty()

        return TargetState(features, pickMode, ballOff, ruleMode, ruleTitles, fakeName, fakeCode, adSdkLines, adUrlLines)
    }
}
