package com.example.selfxposed

import android.app.Activity
import android.content.Intent
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
import com.example.selfxposed.ui.UpdateBlockScreen
import com.example.selfxposed.ui.DialogRulesScreen
import com.example.selfxposed.ui.HintStyleScreen
import com.example.selfxposed.ui.KeywordScreen
import com.example.selfxposed.ui.VersionSpoofScreen
import com.example.selfxposed.ui.AdBlockScreen
import com.example.selfxposed.ui.PurifyRuleScreen
import com.example.selfxposed.ui.PurifyScreen
import com.example.selfxposed.ui.TranslateScreen

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

    /** 每个页面各自的滚动位置，返回上一级时恢复到离开时的位置。 */
    private val pageScrollY = HashMap<String, Int>()

    /** 导航栈：记录来路，返回时直接弹栈（新页面不需要再注册返回目标）。 */
    private val navStack = ArrayDeque<String>()

    /** 启动读取阶段：此时不要被 onResume 提前渲染主页。 */
    private var booting = true
    private var previousPage = PAGE_HOME
    private var selectedPackage = ""
    private var selectedName = ""
    /** 正在查看的净化规则 ID（规则详情页用）。 */
    private var selectedRuleId = ""
    private var logsPackage = ""
    private var logsName = ""
    private var logsBackPage = PAGE_SETTINGS

    /** 日志页只看哪个功能；null = 看该范围内全部日志。 */
    private var logsFeature: String? = null

    /** 弹窗规则页的来源页，返回时回到它。 */
    private var rulesBackPage = PAGE_FEATURES

    /** 净化规则导出：等用户选完保存位置后要写入的内容。 */
    private var pendingExportContent = ""

    /** 净化规则导入：等用户选完文件后要合并进哪个应用。 */
    private var pendingImportPackage = ""

    /** 翻译词条导出：等用户选完保存位置后要写入的内容。 */
    private var pendingDictExportContent = ""

    /** 翻译词条导入：等用户选完文件后要合并进哪个应用。 */
    private var pendingDictImportPackage = ""

    private var systemBackCallback: OnBackInvokedCallback? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.background
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        buildShell()

        // 先显示"正在读取"，等 Shizuku 绑定 + 状态读完再渲染主页，
        // 避免先出现"未开启"再跳变（慢一点但显示正确、不闪）。
        showLoadingPlaceholder()
        Thread {
            try {
                if (ShizukuBridge.hasPermission()) {
                    ShizukuBridge.bind(android.content.ComponentName(this, RemoteService::class.java))
                    ShizukuBridge.ensureBound(6_000L)
                }
                if (WorkMode.canUseRoot(this)) {
                    repo.targets().forEach { pkg ->
                        try {
                            TargetFiles.load(pkg, force = true)
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                booting = false
                enterHomeWithTransition()
                applyPageExtra(intent)
                // 启动完成后立刻做一次工作模式检查（onResume 时还在 booting，会被跳过）
                checkWorkModeHealth()
            }
        }.start()

        // 调试/自动化入口：--ez keep_alive true 等价于在设置里打开「后台常驻」。
        intent?.takeIf { it.hasExtra(EXTRA_KEEP_ALIVE) }?.let {
            val enabled = it.getBooleanExtra(EXTRA_KEEP_ALIVE, false)
            KeepAlive.setEnabled(this, enabled)
        }

        // 打开模块时确保常驻服务在运行（开机后也由 BootReceiver 负责拉起）。
        if (KeepAlive.isEnabled(this)) KeepAliveService.start(this)
        PrefsPermission.makeWorldReadable(this)

        // 首次启动必须先选工作模式，否则进不来。
        if (WorkMode.get(this) == null) askWorkMode()

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

    /** 启动时的读取页：和启动图标同一层，读完后淡入主页。 */
    private fun showLoadingPlaceholder() {
        try {
            content.removeAllViews()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
            }
            val icon = try {
                resources.getDrawable(R.mipmap.ic_launcher, null)
            } catch (_: Throwable) {
                null
            }
            if (icon != null) {
                box.addView(ui.icon(icon, 88))
            }
            box.addView(ui.label("正在读取配置…", 16, Palette.ink, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(24), 0, 0)
            })
            content.addView(box)
        } catch (_: Throwable) {
        }
    }

    /** 读取完成后从读取页淡入主页。 */
    private fun enterHomeWithTransition() {
        renderPage(PAGE_HOME, animate = false)
        try {
            content.alpha = 0f
            content.scaleX = 0.97f
            content.scaleY = 0.97f
            content.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        } catch (_: Throwable) {
        }
    }

    /** 工作模式健康检查：Shizuku / Root 出问题时提示一次。 */
    private var lastModeCheckAt = 0L

    private fun checkWorkModeHealth() {
        val mode = WorkMode.get(this) ?: return
        if (mode == WorkMode.NO_ROOT) return
        val now = System.currentTimeMillis()
        if (now - lastModeCheckAt < 10_000L) return
        lastModeCheckAt = now
        Thread {
            val problem = try {
                when (mode) {
                    WorkMode.SHIZUKU -> when {
                        !ShizukuBridge.isAlive() -> "Shizuku 没有运行，请先启动 Shizuku"
                        !ShizukuBridge.hasPermission() -> "Shizuku 未授权给本模块，请在 Shizuku 里允许"
                        else -> null
                    }

                    WorkMode.ROOT -> if (!RootShell.suExists()) {
                        "root 不可用（su 可能被隐藏 root 的模块挡住了）"
                    } else {
                        null
                    }

                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
            if (problem != null) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // 环境不可用时自动降级为非 Root 模式，避免一直停在失效模式上
                    WorkMode.set(this, WorkMode.NO_ROOT)
                    toast("$problem，已切换为非 Root 模式")
                    if (currentPage == PAGE_SETTINGS) renderPage(PAGE_SETTINGS, animate = false)
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // 启动读取阶段不要提前渲染主页，否则会先闪出"未开启"再跳变
        if (booting) return
        if (::content.isInitialized) checkWorkModeHealth()
        // 权限页：从系统设置授权回来时刷新一次，让通知权限状态及时更新
        if (::content.isInitialized && currentPage == PAGE_PERMISSIONS) {
            renderPage(PAGE_PERMISSIONS, animate = false)
        }
        // 净化页：从目标应用点选完返回时，丢掉缓存并重渲染，保留当前滚动位置
        if (::content.isInitialized && currentPage == PAGE_PURIFY && selectedPackage.isNotBlank()) {
            TargetFiles.invalidate(selectedPackage)
            refresh()
        }
        // 回到主页时刷新一次，让 Hook 连接状态反映最新的握手记录。
        if (::content.isInitialized && currentPage == PAGE_HOME) {
            renderPage(PAGE_HOME, animate = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyPageExtra(intent)
    }

    private fun applyPageExtra(from: Intent?) {        val page = from?.getStringExtra(EXTRA_PAGE) ?: return
        val target = when (page) {
            "features" -> PAGE_FEATURES
            "update_block" -> {
                val first = repo.targets().firstOrNull()
                if (first != null) {
                    selectedPackage = first
                    selectedName = repo.label(first)
                }
                PAGE_UPDATE_BLOCK
            }
            "settings" -> PAGE_SETTINGS
            "dialog_rules" -> PAGE_DIALOG_RULES
            "hint_style" -> PAGE_HINT_STYLE
            "keywords" -> PAGE_KEYWORDS
            "version_spoof" -> {
                val first = repo.targets().firstOrNull()
                if (first != null) {
                    selectedPackage = first
                    selectedName = repo.label(first)
                }
                PAGE_VERSION_SPOOF
            }
            "ad_block" -> {
                val first = repo.targets().firstOrNull()
                if (first != null) {
                    selectedPackage = first
                    selectedName = repo.label(first)
                }
                PAGE_AD_BLOCK
            }
            "permissions" -> PAGE_PERMISSIONS
            "about" -> PAGE_ABOUT
            "help" -> PAGE_HELP
            else -> PAGE_HOME
        }
        if (target != currentPage) renderPage(target, animate = false)
    }

    /** 首次启动的工作模式选择：不可取消、点外部不关闭。 */
    private fun askWorkMode() {
        ui.listDialog(
            title = "选择工作模式",
            items = listOf(
                "Root 模式：直接请求 root（可能被隐藏 root 的模块挡住）",
                "Shizuku 模式：通过 Shizuku 执行命令，适合隐藏 root 的设备",
                "非 Root 模式：不需要任何权限，root 相关功能不可用"
            ),
            showCancel = false,
            onPick = { which ->
                when (which) {
                    0 -> {
                        Thread {
                            val granted = RootShell.hasRoot()
                            runOnUiThread {
                                if (granted) {
                                    WorkMode.set(this, WorkMode.ROOT)
                                    renderPage(currentPage, animate = false)
                                } else {
                                    toast("未获得 root 权限，可改用 Shizuku 或非 Root 模式")
                                    askWorkMode()
                                }
                            }
                        }.start()
                    }

                    1 -> {
                        if (!ShizukuBridge.isAlive()) {
                            toast("Shizuku 没有在运行，请先启动 Shizuku")
                            askWorkMode()
                            return@listDialog
                        }
                        ShizukuBridge.requestPermission()
                        ShizukuBridge.bind(android.content.ComponentName(this, RemoteService::class.java))
                        if (ShizukuBridge.hasPermission()) {
                            WorkMode.set(this, WorkMode.SHIZUKU)
                            renderPage(currentPage, animate = false)
                        } else {
                            toast("请先在 Shizuku 里允许「自用模块」使用")
                            askWorkMode()
                        }
                    }

                    else -> {
                        WorkMode.set(this, WorkMode.NO_ROOT)
                        renderPage(currentPage, animate = false)
                    }
                }
            }
        )
    }

    private fun buildShell() {
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

        root = LinearLayout(this).apply {
            // 横屏：左侧竖排导航 + 右侧内容铺满；竖屏：顶部标题 + 内容 + 底部导航
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isLandscape) Gravity.TOP else Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Palette.background)
        }

        val maxWidth = minOf(resources.displayMetrics.widthPixels, ui.dp(520))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ui.dp(20),
                ui.dp(if (isLandscape) 12 else 18),
                ui.dp(20),
                ui.dp(if (isLandscape) 8 else 14)
            )
        }
        header.addView(ui.label("自用模块", if (isLandscape) 18 else 22, Palette.ink, true))
        header.addView(ui.label("LSPosed · 模块管理", 12, Palette.muted, false).apply {
            setPadding(0, ui.dp(3), 0, 0)
        })
        header.addView(ui.label("v${versionName()} · 内测版", 11, Palette.accent, true).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })

        scroll = ScrollView(this).apply { clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(20))
        }
        scroll.addView(content)

        navHome = navItem("主页")
        navFeatures = navItem("功能")
        navSettings = navItem("设置")

        if (isLandscape) {
            // 左侧竖排导航（铺满高度），右侧内容区铺满，不留白边
            val side = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(10), ui.dp(20), ui.dp(10), ui.dp(20))
                setBackgroundColor(Palette.background)
            }
            side.addView(ui.label("自用", 13, Palette.accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, ui.dp(16))
            })
            listOf(navHome, navFeatures, navSettings).forEachIndexed { index, item ->
                item.gravity = Gravity.CENTER
                side.addView(item, LinearLayout.LayoutParams(-1, ui.dp(52)).apply {
                    if (index > 0) topMargin = ui.dp(10)
                })
            }
            root.addView(side, LinearLayout.LayoutParams(ui.dp(104), -1))

            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(header, LinearLayout.LayoutParams(-1, -2))
            right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            root.addView(header, LinearLayout.LayoutParams(maxWidth, -2))
            root.addView(scroll, LinearLayout.LayoutParams(maxWidth, 0, 1f))

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
            nav.addView(navHome, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            nav.addView(navFeatures, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            nav.addView(navSettings, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            navWrap.addView(nav, LinearLayout.LayoutParams(-1, -2))
            root.addView(navWrap, LinearLayout.LayoutParams(maxWidth, -2))
        }

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

    private fun navItem(title: String) = ui.label(title, 14, Palette.muted, true).apply {
        gravity = Gravity.CENTER
        background = ui.rounded(Color.TRANSPARENT, 24)
    }

    private fun updateNav(page: String, animate: Boolean) {
        val group = when (page) {
            PAGE_FEATURES, PAGE_UPDATE_BLOCK, PAGE_DIALOG_RULES, PAGE_HINT_STYLE, PAGE_KEYWORDS,
            PAGE_VERSION_SPOOF, PAGE_AD_BLOCK, PAGE_PURIFY, PAGE_PURIFY_RULE, PAGE_TRANSLATE -> 1
            PAGE_SETTINGS, PAGE_PERMISSIONS, PAGE_ABOUT, PAGE_LOGS -> 2
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
                    .setDuration(220).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            } else {
                item.alpha = 1f
                item.scaleX = 1f
                item.scaleY = 1f
            }
        }
    }

    private fun renderPage(page: String, animate: Boolean, fromBack: Boolean = false) {
        val direction = if (page == PAGE_HOME && currentPage != PAGE_HOME) -1f else 1f
        // 记住离开当前页时的滚动位置，回到该页时恢复
        if (::content.isInitialized) {
            pageScrollY[currentPage] = scroll.scrollY
        }
        // 记住导航路径：返回时直接弹栈，新页面无需再逐个注册返回目标
        if (!fromBack && page != currentPage) {
            navStack.addLast(currentPage)
            if (navStack.size > 30) navStack.removeFirst()
        }
        currentPage = page
        updateNav(page, animate)

        content.animate().cancel()
        if (animate) {
            content.translationX = ui.dp(22) * direction
            content.alpha = 0.72f
            content.animate().translationX(0f).alpha(1f).setDuration(280)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        } else {
            content.translationX = 0f
            content.alpha = 1f
        }

        content.removeAllViews()
        when (page) {
            PAGE_HOME -> HomeScreen(this).render(content)
            PAGE_FEATURES -> FeaturesScreen(this).render(content)
            PAGE_UPDATE_BLOCK -> UpdateBlockScreen(this, selectedPackage, selectedName).render(content)
            PAGE_DIALOG_RULES -> DialogRulesScreen(this, selectedPackage.ifBlank { null }).render(content)
            PAGE_HINT_STYLE -> HintStyleScreen(this, selectedPackage.ifBlank { null }).render(content)
            PAGE_KEYWORDS -> KeywordScreen(this, selectedPackage, selectedName).render(content)
            PAGE_VERSION_SPOOF -> VersionSpoofScreen(this, selectedPackage, selectedName).render(content)
            PAGE_AD_BLOCK -> AdBlockScreen(this, selectedPackage, selectedName).render(content)
            PAGE_PURIFY -> PurifyScreen(this, selectedPackage, selectedName).render(content)
            PAGE_PURIFY_RULE -> PurifyRuleScreen(
                this, selectedPackage, selectedName, selectedRuleId
            ).render(content)
            PAGE_TRANSLATE -> TranslateScreen(this, selectedPackage, selectedName).render(content)
            PAGE_SETTINGS -> SettingsScreen(this).render(content)
            PAGE_PERMISSIONS -> PermissionsScreen(this).render(content)
            PAGE_ABOUT -> AboutScreen(this).render(content)
            PAGE_LOGS -> LogsScreen(this, logsPackage, logsName, logsFeature).render(content)
            PAGE_HELP -> HelpScreen(this).render(content)
            PAGE_PICKER -> PickerScreen(this).render(content)
        }
        // 恢复该页面上次的滚动位置（新页面从顶部开始）
        val target = pageScrollY[page] ?: 0
        scroll.scrollTo(0, target)
        scroll.post { scroll.scrollTo(0, target) }
    }

    // ---- ScreenHost ----

    override fun openHome() = renderPage(PAGE_HOME, animate = true)

    override fun openFeatures() = renderPage(PAGE_FEATURES, animate = true)

    override fun openSettings() = renderPage(PAGE_SETTINGS, animate = true)

    override fun openPermissions() = renderPage(PAGE_PERMISSIONS, animate = true)

    override fun openAbout() = renderPage(PAGE_ABOUT, animate = true)

    override fun openDialogRules(pkg: String?, name: String?) {
        selectedPackage = pkg.orEmpty()
        selectedName = name.orEmpty()
        rulesBackPage = currentPage
        renderPage(PAGE_DIALOG_RULES, animate = true)
    }

    /** 从弹窗规则页返回来源页。 */
    override fun backFromRules() {
        renderPage(rulesBackPage, animate = true, fromBack = true)
    }

    override fun openHintStyle(pkg: String?, name: String?) {
        selectedPackage = pkg.orEmpty()
        selectedName = name.orEmpty()
        renderPage(PAGE_HINT_STYLE, animate = true)
    }

    override fun openKeywords(pkg: String, name: String) {
        selectedPackage = pkg
        selectedName = name
        renderPage(PAGE_KEYWORDS, animate = true)
    }

    override fun openVersionSpoof(pkg: String, name: String) {
        selectedPackage = pkg
        selectedName = name
        renderPage(PAGE_VERSION_SPOOF, animate = true)
    }

    override fun openAdBlock(pkg: String, name: String) {
        selectedPackage = pkg
        selectedName = name
        renderPage(PAGE_AD_BLOCK, animate = true)
    }

    override fun openPurify(pkg: String, name: String) {
        selectedPackage = pkg
        selectedName = name
        renderPage(PAGE_PURIFY, animate = true)
    }

    override fun openPurifyRule(pkg: String, name: String, ruleId: String) {
        selectedPackage = pkg
        selectedName = name
        selectedRuleId = ruleId
        renderPage(PAGE_PURIFY_RULE, animate = true)
    }

    /** 导出净化规则：用系统的"新建文件"选择器，不需要任何存储权限。 */
    override fun exportPurifyRules(pkg: String, name: String, content: String) {
        pendingExportContent = content
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "purify-$pkg.txt")
            }
            startActivityForResult(intent, REQ_EXPORT)
        } catch (_: Throwable) {
            toast("没有可用的文件选择器")
        }
    }

    /** 导入净化规则：读文本 → 按 id 去重后追加 → 后台写回目标应用。 */
    override fun importPurifyRules(pkg: String, name: String) {
        pendingImportPackage = pkg
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            startActivityForResult(intent, REQ_IMPORT)
        } catch (_: Throwable) {
            toast("没有可用的文件选择器")
        }
    }

    override fun openTranslate(pkg: String, name: String) {
        selectedPackage = pkg
        selectedName = name
        renderPage(PAGE_TRANSLATE, animate = true)
    }

    /** 导出翻译词条：用系统的"新建文件"选择器，不需要存储权限。 */
    override fun exportTranslateDict(pkg: String, name: String, content: String) {
        pendingDictExportContent = content
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "translate-$pkg.txt")
            }
            startActivityForResult(intent, REQ_DICT_EXPORT)
        } catch (_: Throwable) {
            toast("没有可用的文件选择器")
        }
    }

    /** 导入翻译词条：读文本 → 同名覆盖 → 后台写回目标应用。 */
    override fun importTranslateDict(pkg: String, name: String) {
        pendingDictImportPackage = pkg
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            startActivityForResult(intent, REQ_DICT_IMPORT)
        } catch (_: Throwable) {
            toast("没有可用的文件选择器")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_DICT_EXPORT -> {
                val text = pendingDictExportContent
                pendingDictExportContent = ""
                if (text.isEmpty()) return
                Thread {
                    val ok = try {
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        true
                    } catch (_: Throwable) {
                        false
                    }
                    runOnUiThread { toast(if (ok) "词条已导出" else "导出失败") }
                }.start()
            }

            REQ_DICT_IMPORT -> {
                val pkg = pendingDictImportPackage
                pendingDictImportPackage = ""
                if (pkg.isEmpty()) return
                toast("正在读取词条…")
                Thread {
                    val imported = try {
                        contentResolver.openInputStream(uri)?.use {
                            TranslateDict.parse(it.readBytes().toString(Charsets.UTF_8))
                        }.orEmpty()
                    } catch (_: Throwable) {
                        emptyMap()
                    }
                    if (imported.isEmpty()) {
                        runOnUiThread { toast("文件里没有可用的词条") }
                        return@Thread
                    }
                    // 同名以文件里的为准；其余已有词条保留
                    val merged = TranslateDict.readRemoteUser(pkg).toMutableMap()
                    merged.putAll(imported)
                    val ok = TranslateDict.writeRemoteUser(pkg, merged)
                    TargetFiles.invalidate(pkg)
                    runOnUiThread {
                        toast(
                            if (ok) "已导入 ${imported.size} 条词条，重启目标应用生效"
                            else "导入失败，检查 Root / Shizuku 授权"
                        )
                        if (ok && currentPage == PAGE_TRANSLATE) refresh()
                    }
                }.start()
            }

            REQ_EXPORT -> {
                val text = pendingExportContent
                pendingExportContent = ""
                if (text.isEmpty()) return
                Thread {
                    val ok = try {
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        true
                    } catch (_: Throwable) {
                        false
                    }
                    runOnUiThread { toast(if (ok) "规则已导出" else "导出失败") }
                }.start()
            }

            REQ_IMPORT -> {
                val pkg = pendingImportPackage
                pendingImportPackage = ""
                if (pkg.isEmpty()) return
                toast("正在读取规则…")
                Thread {
                    val imported = try {
                        contentResolver.openInputStream(uri)?.use {
                            PurifyRules.parse(it.readBytes().toString(Charsets.UTF_8))
                        }.orEmpty()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    if (imported.isEmpty()) {
                        runOnUiThread { toast("文件里没有可用的规则") }
                        return@Thread
                    }
                    // 按 id 去重：已经在用的规则不重复添加
                    val merged = PurifyRules.readRemote(pkg).toMutableList()
                    val knownIds = merged.map { it.id }.toMutableSet()
                    var added = 0
                    imported.forEach { rule ->
                        if (knownIds.add(rule.id)) {
                            merged.add(rule.copy(hits = 0))
                            added++
                        }
                    }
                    val ok = added > 0 && PurifyRules.writeRemote(pkg, merged)
                    TargetFiles.invalidate(pkg)
                    runOnUiThread {
                        toast(
                            if (ok) "已导入 $added 条规则，重启目标应用生效"
                            else "导入失败，检查 Root / Shizuku 授权"
                        )
                        if (ok && currentPage == PAGE_PURIFY) refresh()
                    }
                }.start()
            }
        }
    }

    override fun openUpdateBlock(pkg: String, name: String) {
        selectedPackage = pkg
        selectedName = name
        renderPage(PAGE_UPDATE_BLOCK, animate = true)
    }

    override fun openLogs(pkg: String, name: String, feature: String?) {
        logsPackage = pkg
        logsName = name
        logsFeature = feature
        // 记住从哪进来的，返回时回原页面
        logsBackPage = currentPage
        renderPage(PAGE_LOGS, animate = true)
    }

    /** 从日志页返回来源页。 */
    override fun backFromLogs() {
        // 通用：回到进入日志前的页面，不再逐个页面加映射（避免新页面漏注册）
        val target = when {
            selectedPackage.isEmpty() -> PAGE_SETTINGS
            logsBackPage.isBlank() || logsBackPage == PAGE_LOGS -> PAGE_SETTINGS
            else -> logsBackPage
        }
        renderPage(target, animate = true, fromBack = true)
    }

    override fun openHelp() {
        previousPage = currentPage
        renderPage(PAGE_HELP, animate = true)
    }

    override fun openPicker() = renderPage(PAGE_PICKER, animate = true)

    override fun refresh() {
        // 记住滚动位置，刷新后恢复，避免每次操作都跳回顶部
        val scrollY = scroll.scrollY
        renderPage(currentPage, animate = false)
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    /**
     * 自绘提示条：样式跟软件主题一致（白底深色字 + 圆角），显示 2 秒、淡出 1 秒，
     * 新提示直接覆盖旧的。
     * 不用系统 Toast —— 它的时长只有 2s/3.5s 两档，凑时长只能重复 show，看起来像弹两遍。
     */
    private var toastView: TextView? = null
    private var lastToastMessage: String? = null
    private var lastToastAt = 0L
    private val toastHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fadeOutToast = Runnable {
        val view = toastView ?: return@Runnable
        view.animate()
            .alpha(0f)
            .setDuration(1_000L)
            .withEndAction {
                try {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                } catch (_: Throwable) {
                }
                if (toastView === view) toastView = null
            }
            .start()
    }

    override fun toast(message: String) {
        // 防抖：500ms 内的重复消息不再弹（避免一次操作触发两次回调）
        val now = System.currentTimeMillis()
        if (message == lastToastMessage && now - lastToastAt < 500L) return
        lastToastMessage = message
        lastToastAt = now
        try {
            val root = window.decorView as? android.view.ViewGroup ?: return
            // 覆盖旧提示
            toastView?.let {
                toastHandler.removeCallbacks(fadeOutToast)
                it.animate().cancel()
                (it.parent as? android.view.ViewGroup)?.removeView(it)
            }
            toastView = null

            val view = TextView(this).apply {
                text = message
                setTextColor(Palette.ink)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
                background = ui.rounded(Palette.surface, 18)
                elevation = ui.dp(6).toFloat()
            }
            val params = android.widget.FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = ui.dp(150)
                leftMargin = ui.dp(24)
                rightMargin = ui.dp(24)
            }
            root.addView(view, params)
            toastView = view
            toastHandler.removeCallbacks(fadeOutToast)
            // 显示 2 秒后，用 1 秒淡化消失
            toastHandler.postDelayed(fadeOutToast, 2_000L)
        } catch (_: Throwable) {
        }
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
        // 通用返回：直接弹导航栈，新页面无需再注册返回目标
        val previous = navStack.removeLastOrNull()
        if (previous != null && previous != currentPage) {
            renderPage(previous, animate = true, fromBack = true)
            return
        }
        if (currentPage == PAGE_LOGS) {
            backFromLogs()
            return
        }
        if (currentPage == PAGE_DIALOG_RULES) {
            backFromRules()
            return
        }
        if (currentPage != PAGE_HOME) {
            renderPage(PAGE_HOME, animate = true, fromBack = true)
        } else {
            finish()
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
        private const val PAGE_UPDATE_BLOCK = "update_block"
        private const val PAGE_DIALOG_RULES = "dialog_rules"
        private const val PAGE_HINT_STYLE = "hint_style"
        private const val PAGE_KEYWORDS = "keywords"
        private const val PAGE_VERSION_SPOOF = "version_spoof"
        private const val PAGE_AD_BLOCK = "ad_block"
        private const val PAGE_PURIFY = "purify"
        private const val PAGE_PURIFY_RULE = "purify_rule"
        private const val PAGE_TRANSLATE = "translate"
        private const val PAGE_SETTINGS = "settings"
        private const val PAGE_PERMISSIONS = "permissions"
        private const val PAGE_ABOUT = "about"
        private const val PAGE_LOGS = "logs"
        private const val PAGE_HELP = "help"
        private const val PAGE_PICKER = "picker"
        private val HOME_PAGES = setOf(PAGE_HOME, PAGE_LOGS, PAGE_HELP, PAGE_PICKER)

        private const val EXTRA_KEEP_ALIVE = "keep_alive"
        private const val EXTRA_PAGE = "page"

        /** 净化规则导入导出的请求码。 */
        private const val REQ_EXPORT = 1001
        private const val REQ_IMPORT = 1002

        /** 翻译词条导入导出的请求码。 */
        private const val REQ_DICT_EXPORT = 1003
        private const val REQ_DICT_IMPORT = 1004
    }
}
