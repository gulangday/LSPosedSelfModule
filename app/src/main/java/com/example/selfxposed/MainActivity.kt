package com.example.selfxposed

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(247, 247, 250)
    private val ink = Color.rgb(28, 27, 31)
    private val muted = Color.rgb(102, 99, 110)
    private val accent = Color.rgb(103, 80, 164)
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var navHome: TextView
    private lateinit var navSettings: TextView
    private var currentPage = "home"
    private var previousPage = "home"
    private var selectedPackage = SUPPORTED_APPS.first().first
    private var selectedName = SUPPORTED_APPS.first().second
    private var systemBackCallback: OnBackInvokedCallback? = null

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && currentPage == "home") showHome(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        buildShell()
        showHome(false)
        // Android 13+ uses OnBackInvokedDispatcher for predictive/edge-swipe back.
        // Handle internal pages here so an edge swipe returns to the parent page
        // instead of finishing the Activity straight to the launcher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBackCallback = OnBackInvokedCallback { handleBackNavigation() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                systemBackCallback!!
            )
        }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundColor(bg)
        }
        header.addView(label("自用模块", 22, ink, true))
        header.addView(label("LSPosed · 模块管理", 12, muted, false).apply {
            setPadding(0, dp(3), 0, 0)
        })
        // Visible build marker: makes it easy to confirm that the newly built APK is installed.
        header.addView(label("优化版 8 · 点击区域与配置诊断", 11, accent, true).apply {
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val navWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(12))
            setBackgroundColor(bg)
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.WHITE, dp(24))
            elevation = dp(2).toFloat()
        }
        navHome = navItem("主页")
        navSettings = navItem("设置")
        nav.addView(navHome, LinearLayout.LayoutParams(0, dp(44), 1f))
        nav.addView(navSettings, LinearLayout.LayoutParams(0, dp(44), 1f))
        navWrap.addView(nav, LinearLayout.LayoutParams(-1, -2))
        root.addView(navWrap)
        setContentView(root)
        navHome.setOnClickListener {
            // 当前主页导航已选中时（包括主页的子页面），重复点击不重建页面、不重复播放动画。
            if (currentPage !in setOf("home", "app", "help", "logs")) showHome(true)
        }
        navSettings.setOnClickListener {
            // 已处于设置主页面时不重复切换。
            if (currentPage != "settings") showSettings(true)
        }
    }

    private fun navItem(title: String) = TextView(this).apply {
        text = title
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(muted)
        background = rounded(Color.TRANSPARENT, dp(24))
    }

    private fun updateNav(page: String, animate: Boolean = true) {
        val homeSelected = page == "home" || page == "app" || page == "help" || page == "logs"
        val selected = if (homeSelected) navHome else navSettings
        val unselected = if (homeSelected) navSettings else navHome

        fun applyItem(item: TextView, isSelected: Boolean) {
            item.animate().cancel()
            item.setTextColor(if (isSelected) Color.WHITE else muted)
            item.background = rounded(if (isSelected) accent else Color.TRANSPARENT, dp(24))
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
        applyItem(selected, true)
        applyItem(unselected, false)
    }

    private fun transition(page: String, animate: Boolean = true, direction: Float = 1f) {
        currentPage = page
        updateNav(page, animate)
        content.animate().cancel()
        if (animate) {
            content.translationX = dp(22) * direction
            content.alpha = 0.65f
            content.animate().translationX(0f).alpha(1f).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            content.translationX = 0f
            content.alpha = 1f
        }
    }

    private fun showHome(animate: Boolean = true) {
        transition("home", animate)
        content.removeAllViews()
        content.addView(card().apply {
            background = rounded(Color.rgb(238, 233, 250), dp(20))
            addView(label("模块控制台", 12, accent, true))
            addView(label("让每项功能状态一目了然", 19, ink, true).apply { setPadding(0, dp(6), 0, dp(4)) })
            addView(label("先在 LSPosed 中启用模块并勾选目标作用域，再启动目标应用完成检测。", 12, muted, false))
        })
        content.addView(sectionTitle("目标应用"))
        content.addView(label("开关状态与 LSPosed 作用域不是同一状态。首次勾选作用域后，请启动目标应用完成 Hook 检测；检测成功后才可操作开关。", 13, muted, false).apply {
            setPadding(dp(2), 0, dp(2), dp(14))
        })
        // 先按手机上是否实际安装排序：已安装的在上方；未安装的在下方并按名称排序。
        val sortedApps = SUPPORTED_APPS.map { app ->
            Triple(app, isPackageInstalled(app.first), ConfigClient.config(contentResolver, app.first).optBoolean("enabled", false))
        }.sortedWith(
            compareByDescending<Triple<Pair<String, String>, Boolean, Boolean>> { it.second }
                .thenByDescending { it.second && it.third }
                .thenBy { it.first.second.lowercase(Locale.ROOT) }
        )

        sortedApps.forEach { (app, installed, enabled) ->
            val (pkg, name) = app
            val cfg = ConfigClient.config(contentResolver, pkg)
            // 卡片严格分成左右两个等宽区域：左半边进入设置，右半边仅用于开关。
            val card = card()
            if (!installed) {
                card.background = rounded(Color.rgb(232, 232, 236), dp(18))
                card.alpha = 0.68f
            }

//            val row = LinearLayout(this).apply {
//                orientation = LinearLayout.HORIZONTAL
//                gravity = Gravity.CENTER_VERTICAL
//                minimumHeight = dp(104)
//            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
//                setBackgroundColor(Color.GREEN)
            }

            val leftArea = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = false
                isFocusable = false
                setPadding(dp(14), dp(12), dp(6), dp(12))
                contentDescription = "进入${name}设置"
            }

            val appIcon = if (installed) {
                try { packageManager.getApplicationIcon(pkg) }
                catch (_: Throwable) { getDrawable(android.R.drawable.sym_def_app_icon) }
            } else {
                getDrawable(android.R.drawable.sym_def_app_icon)
            }
            val openSettings = {
                selectedPackage = pkg
                selectedName = name
                previousPage = "home"
                showAppSettings(true)
            }

            val iconView = ImageView(this).apply {
                setImageDrawable(appIcon)
                alpha = if (installed) 1f else 0.55f
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = if (installed) "$name 图标" else "$name（未安装，默认图标）"
            }

            leftArea.addView(
                iconView,
                LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    rightMargin = dp(8)
                }
            )

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL // 确保内容靠左对齐
                isClickable = false
                isFocusable = false
            }

            val nameView = label(name, 15, if (installed) ink else muted, true).apply {

            }
            // 显式设置宽高为 WRAP_CONTENT (-2)，让点击区域紧贴文字
            details.addView(nameView, LinearLayout.LayoutParams(-2, -2))

            val packageView = label(pkg, 10, muted, false).apply {

            }
            details.addView(packageView, LinearLayout.LayoutParams(-2, -2))

            val versionView = label(
                "版本：${if (installed) getVersion(pkg) else "未安装"}",
                10, muted, false
            ).apply {
            }
            details.addView(versionView, LinearLayout.LayoutParams(-2, -2))

            val statusView = label(
                if (installed) "● 已安装" else "○ 未安装",
                10,
                if (installed) Color.rgb(46, 125, 50) else muted,
                true
            ).apply {
                setPadding(0, dp(3), 0, 0)
            }
            details.addView(statusView, LinearLayout.LayoutParams(-2, -2))

            leftArea.addView(
                details,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            leftArea.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> true
                    android.view.MotionEvent.ACTION_UP -> {
                        val sx = event.rawX.toInt()
                        val sy = event.rawY.toInt()

                        fun hit(view: View): Boolean {
                            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) {
                                return false
                            }
                            val pos = IntArray(2)
                            view.getLocationOnScreen(pos)
                            return sx >= pos[0] && sx < pos[0] + view.width &&
                                    sy >= pos[1] && sy < pos[1] + view.height
                        }

                        if (hit(iconView) || hit(nameView) || hit(packageView) ||
                            hit(versionView) || hit(statusView)
                        ) {
                            openSettings()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }

            row.addView(leftArea, LinearLayout.LayoutParams(0, -1, 1f))

            // 右半边不绑定卡片点击事件；只有 CheckBox 本身响应点击。
            val rightArea = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(14), dp(8))
                isClickable = false
                isFocusable = false
            }
            val toggle = CheckBox(this).apply {
                isChecked = if (installed) enabled else false
                isEnabled = installed
                isClickable = installed
                alpha = if (installed) 1f else 0.38f
                contentDescription = "$name 模块开关"
                setOnCheckedChangeListener { _, checked ->
                    if (!isPackageInstalled(pkg)) {
                        isChecked = false
                        isEnabled = false
                        Toast.makeText(this@MainActivity, "目标应用未安装，无法启用", Toast.LENGTH_SHORT).show()
                        return@setOnCheckedChangeListener
                    }
                    cfg.put("enabled", checked)
                    ConfigClient.save(contentResolver, pkg, cfg)
                    ConfigClient.log(contentResolver, pkg, if (checked) "已在模块设置中启用" else "已在模块设置中关闭")
                    Toast.makeText(this@MainActivity, if (checked) "已开启" else "已关闭", Toast.LENGTH_SHORT).show()
                }
            }
            rightArea.addView(toggle)
            row.addView(
                rightArea,
                LinearLayout.LayoutParams(dp(56), -1)
            )

            card.addView(row)
            content.addView(card)
        }
        content.addView(actionCard("使用说明", "了解模块作用域与应用开关") { showHelp(true) })
    }

    private fun showSettings(animate: Boolean = true) {
        transition("settings", animate)
        content.removeAllViews()
        content.addView(sectionTitle("设置"))
        content.addView(actionCard("实时日志", "查看已适配应用的模块运行记录") { showLogsPicker(true) })
        content.addView(actionCard("使用说明", "作用域与模块开关需要同时启用") { showHelp(true) })
        content.addView(actionCard("模块信息", "自用模块 · LSPosed") {
            AlertDialog.Builder(this).setTitle("模块信息")
                .setMessage("自用模块\n仅列出当前版本已明确适配的目标应用。")
                .setPositiveButton("确定", null).show()
        })
    }

    private fun showAppSettings(animate: Boolean = true) {
        transition("app", animate)
        content.removeAllViews()
        val cfg = ConfigClient.config(contentResolver, selectedPackage)
        content.addView(backRow("‹  返回主页") { showHome(true) })
        content.addView(sectionTitle(selectedName))
        content.addView(label(selectedPackage, 12, muted, false))
        val popupSwitch = Switch(this).apply {
            text = "启用提示通知"
            isChecked = cfg.optBoolean("popupEnabled", true)
        }
        content.addView(card().apply { addView(popupSwitch) })
        val edit = EditText(this).apply {
            hint = "提示文字"
            setText(cfg.optString("popupText", "你好"))
            textSize = 16f
            setSingleLine(false)
            minLines = 2
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, dp(12))
        }
        content.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(actionCard("保存设置", "只影响当前目标应用") {
            cfg.put("popupEnabled", popupSwitch.isChecked)
            cfg.put("popupText", edit.text.toString().ifBlank { "你好" })
            ConfigClient.save(contentResolver, selectedPackage, cfg)
            ConfigClient.log(contentResolver, selectedPackage, "设置已保存")
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })
    }

    private fun showLogsPicker(animate: Boolean = true) {
        if (SUPPORTED_APPS.isEmpty()) {
            Toast.makeText(this, "当前没有已适配的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = SUPPORTED_APPS.map { "${it.second} (${it.first})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择应用日志").setItems(labels) { _, which ->
            showLogs(SUPPORTED_APPS[which].first, SUPPORTED_APPS[which].second, true)
        }.setNegativeButton("取消", null).show()
    }

    private fun showLogs(pkg: String, name: String, animate: Boolean = true) {
        if (currentPage != "logs") previousPage = currentPage
        transition("logs", animate)
        content.removeAllViews()
        content.addView(backRow("‹  返回") { if (previousPage == "app") showAppSettings(true) else showSettings(true) })
        content.addView(sectionTitle("$name · 日志"))
        val text = TextView(this).apply {
            setTextColor(ink); textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.WHITE, dp(12))
            val raw = ConfigClient.call(contentResolver, "get_logs", pkg).getString("logs") ?: ""
            this.text = raw.lines().asReversed().joinToString("\n")
                .ifBlank { "暂无日志。打开目标应用并触发模块功能后，记录会显示在这里。" }
        }
        content.addView(text, LinearLayout.LayoutParams(-1, -2))
        content.addView(actionCard("刷新日志", "重新读取当前记录") { showLogs(pkg, name, false) })
        content.addView(actionCard("清空日志", "只清空当前应用的日志") {
            ConfigClient.call(contentResolver, "clear_logs", pkg)
            ConfigClient.log(contentResolver, pkg, "日志已清空")
            showLogs(pkg, name, false)
        })
    }

    private fun showHelp(animate: Boolean = true) {
        previousPage = currentPage
        transition("help", animate)
        content.removeAllViews()
        content.addView(backRow("‹  返回") { if (previousPage == "home") showHome(true) else showSettings(true) })
        content.addView(sectionTitle("使用说明"))
        content.addView(card().apply {
            addView(label("1. 在 LSPosed 管理器安装并启用本模块。\n\n2. 在作用域中勾选已适配的目标应用。\n\n3. 启动一次目标应用，等待模块完成 Hook 环境检测。\n\n4. 回到模块主页，勾选对应应用的开关。\n\n4. 点击应用卡片，配置提示文字并保存。\n\n提示采用 Android Toast；显示时长和淡出由系统控制，界面中的原时间配置会保留，但不能精确控制 Toast 的时长。", 14, ink, false))
        })
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    private fun handleBackNavigation() {
        when (currentPage) {
            "app" -> showHome(true)
            "logs" -> if (previousPage == "app") showAppSettings(true) else showSettings(true)
            "help" -> if (previousPage == "home") showHome(true) else showSettings(true)
            "settings" -> showHome(true)
            else -> finish()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    private fun isHookDetected(pkg: String): Boolean = try {
        val status = ConfigClient.call(contentResolver, "get_hook_status", pkg)
        status.getBoolean("detected", false)
    } catch (_: Throwable) { false }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun getVersion(pkg: String): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).versionName ?: "未知"
    } catch (_: Throwable) { "未安装" }

    private fun sectionTitle(s: String) = label(s, 18, ink, true).apply {
        setPadding(dp(2), dp(12), dp(2), dp(10))
    }
    private fun label(s: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.WHITE, dp(18))
        elevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }
    private fun actionCard(title: String, subtitle: String, action: () -> Unit): View {
        val c = card()
        c.addView(label(title, 15, ink, true))
        c.addView(label(subtitle, 12, muted, false).apply { setPadding(0, dp(5), 0, 0) })
        c.setOnClickListener { action() }
        return c
    }
    private fun backRow(s: String, action: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(accent)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(Color.rgb(238, 233, 250), dp(24)).apply {
            setStroke(dp(1), Color.rgb(210, 201, 235))
        }
        isClickable = true
        isFocusable = true
        elevation = dp(1).toFloat()
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        // 当前版本唯一明确适配的目标应用；未适配应用不会出现在列表中。
        private val SUPPORTED_APPS = listOf("com.kugou.android.lite" to "酷狗概念版")
    }
}
