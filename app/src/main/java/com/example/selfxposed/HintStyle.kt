package com.example.selfxposed

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 「已拦截弹窗」提示条的样式。
 *
 * 两条来源：
 * 1. 预设：存在模块自己的配置里，通过 Provider 下发（targetSdk < 30 的应用免 root 可读）；
 * 2. 自定义：由模块用 root 写进目标应用自己的 files 目录，hook 直接读（targetSdk 35 也能用）。
 */
data class HintStyle(
    val sizeSp: Int,
    val position: String,
    val offsetDp: Int,
    val bgColor: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sizeSp", sizeSp)
        .put("position", position)
        .put("offsetDp", offsetDp)
        .put("bgColor", bgColor)

    fun toText(): String = buildString {
        appendLine("sizeSp=$sizeSp")
        appendLine("position=$position")
        appendLine("offsetDp=$offsetDp")
        appendLine("bgColor=$bgColor")
    }

    companion object {
        val DEFAULT = HintStyle(12, "bottom", 110, "#CC1C1B1F")

        fun fileOf(context: Context): File =
            File(context.filesDir, "selfxposed_hint.conf")

        /** 读目标应用本地的自定义样式；没有配置时返回 null。 */
        fun fromFile(context: Context): HintStyle? = try {
            val file = fileOf(context)
            if (!file.exists()) {
                null
            } else {
                val map = file.readLines()
                    .mapNotNull { line ->
                        val i = line.indexOf('=')
                        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                    }
                    .toMap()
                if (map.isEmpty()) {
                    null
                } else {
                    HintStyle(
                        sizeSp = map["sizeSp"]?.toIntOrNull()?.coerceIn(8, 48) ?: DEFAULT.sizeSp,
                        position = map["position"] ?: DEFAULT.position,
                        offsetDp = map["offsetDp"]?.toIntOrNull()?.coerceIn(0, 800) ?: DEFAULT.offsetDp,
                        bgColor = map["bgColor"] ?: DEFAULT.bgColor
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }

        fun fromJson(json: JSONObject?): HintStyle? {
            if (json == null) return null
            if (json.length() == 0) return null
            return HintStyle(
                sizeSp = json.optInt("sizeSp", DEFAULT.sizeSp).coerceIn(8, 48),
                position = json.optString("position", DEFAULT.position),
                offsetDp = json.optInt("offsetDp", DEFAULT.offsetDp).coerceIn(0, 800),
                bgColor = json.optString("bgColor", DEFAULT.bgColor)
            )
        }
    }
}
