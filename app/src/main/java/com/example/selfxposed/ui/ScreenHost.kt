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
    fun openApp(pkg: String, name: String)
    fun openUpdateBlock(pkg: String, name: String)
    fun openDialogRules(pkg: String?, name: String?)
    fun openHintStyle(pkg: String?, name: String?)
    fun openKeywords(pkg: String, name: String)
    fun openLogs(pkg: String, name: String)
    fun backFromLogs()
    fun openHelp()
    fun openPicker()

    /** 用当前页面内容重新渲染（不播放入场动画）。 */
    fun refresh()
    fun toast(message: String)
    fun versionName(): String
    fun versionCode(): Int
}
