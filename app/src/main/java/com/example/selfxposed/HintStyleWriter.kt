package com.example.selfxposed

/** 把提示条样式用 root 推到每个目标应用的私有目录，hook 侧直接读文件。 */
object HintStyleWriter {
    fun apply(context: android.content.Context, style: HintStyle, targets: List<String>): Boolean {
        if (targets.isEmpty()) return false
        return try {
            val tmp = java.io.File(context.cacheDir, "hint_style.conf")
            tmp.writeText(style.toText())
            var ok = true
            targets.forEach { pkg ->
                val cmd = listOf(
                    "mkdir -p /data/data/$pkg/files",
                    "cp '${tmp.absolutePath}' /data/data/$pkg/files/selfxposed_hint.conf",
                    "chown \$(stat -c %u:%g /data/data/$pkg) /data/data/$pkg/files/selfxposed_hint.conf",
                    "chmod 660 /data/data/$pkg/files/selfxposed_hint.conf"
                ).joinToString("; ")
                val out = RootShell.exec(cmd)
                if (out.contains("No such file") || out.contains("denied")) ok = false
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }
}
