package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * 广告域名黑名单（不依赖 SDK 类名，混淆也没关系）。
 *
 * 优先级：目标应用本地 `files/selfxposed_ad_urls.conf`（用户可编辑）> 内置默认。
 */
object AdUrlBlacklist {
    private const val FILE = "selfxposed_ad_urls.conf"
    private const val TTL_MS = 3_000L

    val DEFAULT_HOSTS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "gdt.qq.com",
        "pgdt.gtimg.cn",
        "pangolin-sdk-toutiao.com",
        "pglstatp-toutiao.com",
        "mobads.baidu.com",
        "cpro.baidu.com",
        "ad.umeng.com",
        "umtrack.com",
        "kwad.com"
    )

    @Volatile
    private var cache: List<String>? = null

    @Volatile
    private var cachedAt = 0L

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
        val result = if (fromFile.isEmpty()) DEFAULT_HOSTS else fromFile
        cache = result
        cachedAt = now
        return result
    }

    fun invalidate() {
        cache = null
        cachedAt = 0L
    }

    /** URL 的 host 命中任一域名即视为广告。 */
    fun matches(context: Context, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            java.net.URI(url).host?.lowercase() ?: return false
        } catch (_: Throwable) {
            return false
        }
        return load(context).any { host == it || host.endsWith(".$it") }
    }
}
