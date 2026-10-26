package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * 广告 SDK 黑名单。
 *
 * 优先级：目标应用本地 `files/selfxposed_ad_sdk.conf`（用户可编辑）> 内置默认列表。
 * 只按**类名前缀**匹配，避免依赖具体类名（SDK 常混淆）。
 */
object AdSdkBlacklist {
    private const val FILE = "selfxposed_ad_sdk.conf"
    private const val TTL_MS = 3_000L

    /** 内置默认前缀。 */
    val DEFAULT_PREFIXES = listOf(
        "com.bytedance.sdk.openadsdk",   // 字节 / 穿山甲
        "com.qq.e",                      // 腾讯优量汇
        "com.kwad.sdk",                  // 快手
        "com.baidu.mobads",              // 百度
        "com.anythink",                  // TopOn
        "com.pangle",                    // 穿山甲新包名
        "com.umeng.ads"                  // 友盟
    )

    @Volatile
    private var cache: List<String>? = null

    @Volatile
    private var cachedAt = 0L

    /** 读当前生效的黑名单（用户配置优先，3 秒缓存）。 */
    fun load(context: Context): List<String> {
        val now = System.currentTimeMillis()
        val hit = cache
        if (hit != null && now - cachedAt < TTL_MS) return hit
        val fromFile = try {
            val file = File(context.filesDir, FILE)
            if (file.exists()) {
                file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
        val result = if (fromFile.isEmpty()) DEFAULT_PREFIXES else fromFile
        cache = result
        cachedAt = now
        return result
    }

    /** 清缓存：保存黑名单后调用，让 hook 立刻用新列表。 */
    fun invalidate() {
        cache = null
        cachedAt = 0L
    }

    fun matches(context: Context, className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return load(context).any { className.startsWith(it) }
    }

    fun matchesInstance(context: Context, any: Any?): Boolean =
        matches(context, any?.javaClass?.name)
}
