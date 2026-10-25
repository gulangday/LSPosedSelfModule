package com.example.selfxposed

/**
 * 弹窗拦截规则是 hook 写在目标应用自己数据目录里的（为了让 targetSdk 35 的应用也能用）。
 * 模块普通权限读不到，这里用 root 读取与清除。
 */
object DialogRules {
    data class Rule(val mode: String, val titles: List<String>)

    private fun path(pkg: String) = "/data/data/$pkg/shared_prefs/selfxposed_block.xml"

    fun read(pkg: String): Rule? {
        val xml = RootShell.exec("cat ${path(pkg)} 2>/dev/null")
        if (xml.isBlank()) return null
        val mode = Regex("<string name=\"mode\">([^<]*)</string>")
            .find(xml)?.groupValues?.get(1).orEmpty()
        if (mode.isBlank()) return null
        val titles = Regex("<set name=\"titles\">(.*?)</set>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?.let { block -> Regex("<string>(.*?)</string>").findAll(block).map { it.groupValues[1] }.toList() }
            .orEmpty()
        return Rule(mode, titles)
    }

    fun clear(pkg: String): Boolean {
        val out = RootShell.exec("rm -f ${path(pkg)} ${path(pkg)}.bak 2>/dev/null; echo done")
        return out.contains("done")
    }

    fun modeLabel(mode: String): String = when (mode) {
        "block" -> "全部拦截"
        "allow" -> "不拦截"
        "titles" -> "只拦指定标题"
        else -> "未设置"
    }
}
