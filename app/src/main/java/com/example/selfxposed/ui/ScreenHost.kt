package com.example.selfxposed.ui

import com.example.selfxposed.data.ModuleRepository

/**
 * 页面与宿主 Activity 之间的契约：页面只负责渲染，导航与状态由 MainActivity 持有。
 */
interface ScreenHost {
    val ui: Ui
    val repo: ModuleRepository

    fun openHome()
    fun openFeatures()
    fun openSettings()
    fun openPermissions()
    fun openAbout()
    fun openUpdateBlock(pkg: String, name: String)
    fun openDialogRules(pkg: String?, name: String?)
    fun backFromRules()
    fun openHintStyle(pkg: String?, name: String?)
    fun openKeywords(pkg: String, name: String)
    fun openVersionSpoof(pkg: String, name: String)
    fun openAdBlock(pkg: String, name: String)
    fun openPurify(pkg: String, name: String)

    /** 打开某条净化规则的详情页（规则列表里点击卡片进入）。 */
    fun openPurifyRule(pkg: String, name: String, ruleId: String)

    fun openTranslate(pkg: String, name: String)

    /** 把自定义翻译词条导出成文本。 */
    fun exportTranslateDict(pkg: String, name: String, content: String)

    /** 从文本文件导入翻译词条（追加，同名覆盖）。 */
    fun importTranslateDict(pkg: String, name: String)

    /** 把净化规则导出成文本（弹系统文件选择器让用户挑保存位置）。 */
    fun exportPurifyRules(pkg: String, name: String, content: String)

    /** 从文本文件导入净化规则（追加，不覆盖现有的）。 */
    fun importPurifyRules(pkg: String, name: String)
    /**
     * 打开日志页。
     * @param feature 只显示这个功能的日志；传 null 表示显示该范围内全部日志。
     */
    fun openLogs(pkg: String, name: String, feature: String? = null)
    fun backFromLogs()
    fun openHelp()
    fun openPicker()
    fun openLsposed()

    /** 用当前页面内容重新渲染（不播放入场动画）。 */
    fun refresh()
    fun toast(message: String)
    fun versionName(): String
    fun versionCode(): Int
}
