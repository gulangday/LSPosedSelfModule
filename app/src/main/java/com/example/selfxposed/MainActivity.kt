package com.example.selfxposed

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.example.selfxposed.data.ModuleRepository
import com.example.selfxposed.ui.AboutScreen
import com.example.selfxposed.ui.AppSettingsScreen
import com.example.selfxposed.ui.FeaturesScreen
import com.example.selfxposed.ui.HelpScreen
import com.example.selfxposed.ui.HomeScreen
import com.example.selfxposed.ui.LogsScreen
import com.example.selfxposed.ui.Palette
import com.example.selfxposed.ui.PermissionsScreen
import com.example.selfxposed.ui.PickerScreen
import com.example.selfxposed.ui.ScreenHost
import com.example.selfxposed.ui.SettingsScreen
import com.example.selfxposed.ui.Ui

/**
 * 只负责外壳与导航：顶部标题、内容容器、底部两个导航项、系统返回键。
 * 每个页面的内容由 ui/ 下的 Screen 类渲染，数据访问统一走 ModuleRepository。
 */
class MainActivity : Activity(), ScreenHost {
    override val ui: Ui by lazy { Ui(this) }
    override val repo: ModuleRepository by lazy { ModuleRepository(this) }

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var navHome: TextView
    private lateinit var navFeatures: TextView
    private lateinit var navSettings: TextView

    private var currentPage = PAGE_HOME
    private var previousPage = PAGE_HOME
    private var selectedPackage = ""
    private var selectedName = ""
    private var logsPackage = ""
    private var logsName = ""
    private var logsBackPage = PAGE_SETTINGS
    private var systemBackCallback: OnBackInvokedCallback? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.background
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        buildShell()
        renderPage(PAGE_HOME, animate = false)

        // 调试/自动化入口：--ez keep_alive true 等价于在设置里打开「后台常驻」。
        intent?.takeIf { it.hasExtra(EXTRA_KEEP_ALIVE) }?.let {
            val enabled = it.getBooleanExtra(EXTRA_KEEP_ALIVE, false)
            KeepAlive.setEnabled(this, enabled)
        }

        // 打开模块时确保常驻服务在运行（开机后也由 BootReceiver 负责拉起）。
        if (KeepAlive.isEnabled(this)) KeepAliveService.start(this)
        if (intent?.getBooleanExtra(EXTRA_PREVIEW_POPUP, false) == true) showDebugPreview()

        // Android 13+ 使用 OnBackInvokedDispatcher；目标 SDK 仍较低时系统走 onBackPressed。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleBack() }
            systemBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到主页时刷新一次，让 Hook 连接状态反映最新的握手记录。
        if (::content.isInitialized && currentPage == PAGE_HOME) {
            renderPage(PAGE_HOME, animate = false)
        }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.background)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14))
        }
        header.addView(ui.label("自用模块", 22, Palette.ink, true))
        header.addView(ui.label("LSPosed · 模块管理", 12, Palette.muted, false).apply {
            setPadding(0, ui.dp(3), 0, 0)
        })
        header.addView(ui.label("v${versionName()} · 内测版", 11, Palette.accent, true).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        root.addView(header)

        scroll = ScrollView(this).apply { clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val navWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(12))
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = ui.rounded(Color.WHITE, 24)
            elevation = ui.dp(2).toFloat()
        }
        navHome = navItem("主页")
        navFeatures = navItem("功能")
        navSettings = navItem("设置")
        nav.addView(navHome, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        nav.addView(navFeatures, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        nav.addView(navSettings, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        navWrap.addView(nav, LinearLayout.LayoutParams(-1, -2))
        root.addView(navWrap)

        setContentView(root)

        navHome.setOnClickListener {
            if (currentPage !in HOME_PAGES) renderPage(PAGE_HOME, animate = true)
        }
        navFeatures.setOnClickListener {
            if (currentPage != PAGE_FEATURES) renderPage(PAGE_FEATURES, animate = true)
        }
        navSettings.setOnClickListener {
            if (currentPage != PAGE_SETTINGS) renderPage(PAGE_SETTINGS, animate = true)
        }
    }

    /**
     * 调试入口，方便在没有 LSPosed 注入的情况下验证弹窗渲染：
     * adb shell am start -n com.example.selfxposed/.MainActivity \
     *   --ez preview_popup true --es preview_text "hello" --ei preview_show_ms 5000
     */
    private fun showDebugPreview() {
        val text = intent?.getStringExtra(EXTRA_PREVIEW_TEXT) ?: "弹窗预览：白底黑字 · 底部居中"
        val showMs = intent?.getIntExtra(EXTRA_PREVIEW_SHOW_MS, 5000) ?: 5000
        val fadeMs = intent?.getIntExtra(EXTRA_PREVIEW_FADE_MS, 1000) ?: 1000
        window.decorView.postDelayed({
            PopupController.show(this, "debug-preview", text, showMs, fadeMs, restart = true)
        }, 500)
    }

    private fun navItem(title: String) = ui.label(title, 14, Palette.muted, true).apply {
        gravity = Gravity.CENTER
        background = ui.rounded(Color.TRANSPARENT, 24)
    }

    private fun updateNav(page: String, animate: Boolean) {
        val group = when (page) {
            PAGE_FEATURES -> 1
            PAGE_SETTINGS, PAGE_PERMISSIONS, PAGE_ABOUT -> 2
            else -> 0
        }

        listOf(navHome, navFeatures, navSettings).forEachIndexed { index, item ->
            val isSelected = index == group
            item.animate().cancel()
            item.setTextColor(if (isSelected) Color.WHITE else Palette.muted)
            item.background = ui.rounded(if (isSelected) Palette.accent else Color.TRANSPARENT, 24)
            if (animate) {
                item.alpha = 0.82f
                item.scaleX = 0.97f
                item.scaleY = 0.97f
                item.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(320).setInterpolator(DecelerateInterpolator()).start()
            } else {
                item.alpha = 1f
                item.scaleX = 1f
                item.scaleY = 1f
            }
        }
    }

    private fun renderPage(page: String, animate: Boolean) {
        val direction = if (page == PAGE_HOME && currentPage != PAGE_HOME) -1f else 1f
        currentPage = page
        updateNav(page, animate)

        content.animate().cancel()
        if (animate) {
            content.translationX = ui.dp(22) * direction
            content.alpha = 0.65f
            content.animate().translationX(0f).alpha(1f).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            content.translationX = 0f
            content.alpha = 1f
        }

        scroll.scrollTo(0, 0)
        content.removeAllViews()
        when (page) {
            PAGE_HOME -> HomeScreen(this).render(content)
            PAGE_FEATURES -> FeaturesScreen(this).render(content)
            PAGE_SETTINGS -> SettingsScreen(this).render(content)
            PAGE_PERMISSIONS -> PermissionsScreen(this).render(content)
            PAGE_ABOUT -> AboutScreen(this).render(content)
            PAGE_APP -> AppSettingsScreen(this, selectedPackage, selectedName).render(content)
            PAGE_LOGS -> LogsScreen(this, logsPackage, logsName, logsBackPage).render(content)
            PAGE_HELP -> HelpScreen(this).render(content)
            PAGE_PICKER -> PickerScreen(this).render(content)
        }
    }

    // ---- ScreenHost ----

    override fun openHome() = renderPage(PAGE_HOME, animate = true)

    override fun openFeatures() = renderPage(PAGE_FEATURES, animate = true)

    override fun openSettings() = renderPage(PAGE_SETTINGS, animate = true)

    override fun openPermissions() = renderPage(PAGE_PERMISSIONS, animate = true)

    override fun openAbout() = renderPage(PAGE_ABOUT, animate = true)

    override fun openApp(pkg: String, name: String) {
        selectedPackage = pkg
        selectedName = name
        renderPage(PAGE_APP, animate = true)
    }

    override fun openLogs(pkg: String, name: String) {
        logsPackage = pkg
        logsName = name
        logsBackPage = if (currentPage == PAGE_APP) PAGE_APP else PAGE_SETTINGS
        renderPage(PAGE_LOGS, animate = true)
    }

    override fun openHelp() {
        previousPage = currentPage
        renderPage(PAGE_HELP, animate = true)
    }

    override fun openPicker() = renderPage(PAGE_PICKER, animate = true)

    override fun refresh() = renderPage(currentPage, animate = false)

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    @Suppress("DEPRECATION")
    override fun versionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (_: Throwable) {
        0
    }

    // ---- 返回键 ----

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        when (currentPage) {
            PAGE_APP, PAGE_PICKER -> renderPage(PAGE_HOME, animate = true)
            PAGE_LOGS -> renderPage(
                if (logsBackPage == PAGE_APP && selectedPackage.isNotEmpty()) PAGE_APP else PAGE_SETTINGS,
                animate = true
            )
            PAGE_HELP -> renderPage(if (previousPage == PAGE_SETTINGS) PAGE_SETTINGS else PAGE_HOME, animate = true)
            PAGE_PERMISSIONS, PAGE_ABOUT -> renderPage(PAGE_SETTINGS, animate = true)
            PAGE_FEATURES -> renderPage(PAGE_HOME, animate = true)
            PAGE_SETTINGS -> renderPage(PAGE_HOME, animate = true)
            else -> finish()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    companion object {
        private const val PAGE_HOME = "home"
        private const val PAGE_FEATURES = "features"
        private const val PAGE_SETTINGS = "settings"
        private const val PAGE_PERMISSIONS = "permissions"
        private const val PAGE_ABOUT = "about"
        private const val PAGE_APP = "app"
        private const val PAGE_LOGS = "logs"
        private const val PAGE_HELP = "help"
        private const val PAGE_PICKER = "picker"
        private val HOME_PAGES = setOf(PAGE_HOME, PAGE_APP, PAGE_LOGS, PAGE_HELP, PAGE_PICKER)

        private const val EXTRA_PREVIEW_POPUP = "preview_popup"
        private const val EXTRA_PREVIEW_TEXT = "preview_text"
        private const val EXTRA_PREVIEW_SHOW_MS = "preview_show_ms"
        private const val EXTRA_PREVIEW_FADE_MS = "preview_fade_ms"
        private const val EXTRA_KEEP_ALIVE = "keep_alive"
    }
}
