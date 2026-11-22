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
        val adUrlLines: List<String> = emptyList(),
        /** 净化规则（已合并 hook 记下的命中数） */
        val purifyRules: List<PurifyRules.Rule> = emptyList(),
        /** 翻译：用户自己加的词条（英文小写=中文） */
        val translateUser: Map<String, String> = emptyMap(),
        /** 翻译选项：带危险符号的字符串是否跳过（默认 true） */
        val translateSkipSymbols: Boolean = true,
        /** 目标应用最近一次被注入的时间（0 = 从未检测到） */
        val seenAt: Long = 0L
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
        return try {
            val raw = RootShell.exec("D=/data/data/$pkg; " + stateScript(), 6_000L)
            val parsed = parse(raw)
            // 只有命令真正跑起来（带回 @@F 标记）才缓存；
            // Shizuku 未绑定等失败返回空串，不能缓存，否则 30 秒内都显示未开启。
            if (raw.contains("@@F")) cache[pkg] = now to parsed
            parsed
        } catch (_: Throwable) {
            TargetState()
        }
    }

    /**
     * 批量读取：一条 shell 命令循环跑完所有包，每段用 `@@PKG <包名>` 分隔。
     * 逐包 load 会把 root IPC 次数放大成 N 倍，主页刷新一次就要 N 条命令。
     */
    fun loadAll(pkgs: List<String>, force: Boolean = false): Map<String, TargetState> {
        val result = LinkedHashMap<String, TargetState>()
        if (pkgs.isEmpty()) return result
        val now = System.currentTimeMillis()
        val toRead = pkgs.filter { pkg ->
            val cachedAt = cache[pkg]?.first
            force || cachedAt == null || now - cachedAt >= TTL_MS
        }
        pkgs.forEach { pkg -> cache[pkg]?.let { result[pkg] = it.second } }
        if (toRead.isEmpty()) return result

        val command = buildString {
            append("for P in ").append(toRead.joinToString(" ")).append("; do ")
            append("D=/data/data/\$P; echo \"@@PKG \$P\"; ")
            append(stateScript())
            append("done")
        }
        val raw = try {
            RootShell.exec(command, 6_000L)
        } catch (_: Throwable) {
            ""
        }
        if (raw.contains("@@PKG")) {
            var current: String? = null
            val buffer = StringBuilder()
            fun flush() {
                val pkg = current ?: return
                val parsed = parse(buffer.toString())
                cache[pkg] = now to parsed
                result[pkg] = parsed
                buffer.setLength(0)
            }
            raw.lines().forEach { line ->
                if (line.startsWith("@@PKG ")) {
                    flush()
                    current = line.removePrefix("@@PKG ").trim()
                } else {
                    buffer.append(line).append('\n')
                }
            }
            flush()
        }

        // 没读到的包（命令失败、Shizuku 未就绪）：留一个短缓存，
        // 页面才不会停在「读取中」，也不会因为没有缓存反复重试。
        toRead.forEach { pkg ->
            if (!result.containsKey(pkg)) {
                val empty = TargetState()
                cache[pkg] = (now - (TTL_MS - 5_000L)) to empty
                result[pkg] = empty
            }
        }
        return result
    }

    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 后台读取并在主线程回调：页面首帧不再同步等 root 命令。
     * 同一个包同时只跑一次；读失败也留一个短缓存，页面才不会卡在占位或反复重试。
     */
    fun loadAsync(activity: android.app.Activity, pkg: String, force: Boolean = false, onDone: () -> Unit = {}) {
        if (!loading.add(pkg)) return
        Thread {
            try {
                val state = try {
                    load(pkg, force)
                } catch (_: Throwable) {
                    TargetState()
                }
                if (cache[pkg] == null) {
                    // 把时间往前拨，让这个空状态按短 TTL 过期（5 秒）
                    val shortTtl = 5_000L
                    cache[pkg] = (System.currentTimeMillis() - (TTL_MS - shortTtl)) to state
                }
            } finally {
                loading.remove(pkg)
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onDone()
            }
        }.start()
    }

    /** 单个包的读取脚本：用环境变量 D 指向目标应用的数据目录。 */
    private fun stateScript(): String = buildString {
        // 每个 cat 后面补一个 echo：兼容文件结尾没有换行的情况，
        // 否则下一段的标记会粘到这一行末尾，导致解析成 "1@@M" 这种错误值。
        append("echo '@@F'; cat \$D/files/selfxposed_features.conf 2>/dev/null; echo; ")
        append("echo '@@M'; cat \$D/files/selfxposed_pick_mode.conf 2>/dev/null; echo; ")
        append("echo '@@B'; test -f \$D/files/selfxposed_ball_off && echo 1 || echo 0; ")
        append("echo '@@E'; cat \$D/files/selfxposed_seen 2>/dev/null; echo; ")
        append("echo '@@U'; cat \$D/files/selfxposed_update_block.conf 2>/dev/null; echo; ")
        append("echo '@@A'; cat \$D/files/selfxposed_ad_sdk.conf 2>/dev/null; echo; ")
        append("echo '@@W'; cat \$D/files/selfxposed_ad_urls.conf 2>/dev/null; echo; ")
        append("echo '@@P'; cat \$D/files/selfxposed_purify.conf 2>/dev/null; echo; ")
        append("echo '@@Y'; cat \$D/files/selfxposed_purify_hits.conf 2>/dev/null; echo; ")
        append("echo '@@K'; cat \$D/files/selfxposed_purify_skipped.conf 2>/dev/null; echo; ")
        append("echo '@@T'; cat \$D/files/selfxposed_translate_user.conf 2>/dev/null; echo; ")
        append("echo '@@S'; cat \$D/files/selfxposed_translate.conf 2>/dev/null; echo; ")
        append("echo '@@R'; cat \$D/shared_prefs/selfxposed_block.xml 2>/dev/null; echo")
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
                line == "@@E" -> current = "E"
                line == "@@U" -> current = "U"
                line == "@@A" -> current = "A"
                line == "@@W" -> current = "W"
                line == "@@P" -> current = "P"
                line == "@@Y" -> current = "Y"
                line == "@@K" -> current = "K"
                line == "@@T" -> current = "T"
                line == "@@S" -> current = "S"
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
        val seenAt = sections["E"]?.toString().orEmpty().trim().toLongOrNull() ?: 0L

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

        val purifyRules = PurifyRules.parse(
            sections["P"]?.toString().orEmpty(),
            PurifyRules.parseHits(sections["Y"]?.toString().orEmpty()),
            PurifyRules.parseSkipped(sections["K"]?.toString().orEmpty())
        )

        return TargetState(
            features, pickMode, ballOff, ruleMode, ruleTitles, fakeName, fakeCode, adSdkLines, adUrlLines,
            purifyRules = purifyRules,
            translateUser = TranslateDict.parse(sections["T"]?.toString().orEmpty()),
            translateSkipSymbols = TranslateDict.parseOptions(sections["S"]?.toString().orEmpty()),
            seenAt = seenAt
        )
    }
}
