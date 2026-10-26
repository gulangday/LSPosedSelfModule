package com.example.selfxposed

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 注入入口：不再限定单一目标包名。
 *
 * LSPosed 只会把模块注入到“作用域里勾选过”的应用，因此这里对任何被注入的包都安装 Hook；
 * 是否真正弹窗完全由每个应用自己的配置（enabled / popupEnabled）决定。
 * 未添加为目标应用、或从未打开过的应用，读到的配置就是默认的 enabled = false。
 */
class MainHook : IXposedHookLoadPackage {

    /** 拦截拾取：悬浮球 + 点选弹窗。 */
    @Volatile
    private var picking = false
    private val floatingBalls = java.util.WeakHashMap<Activity, android.view.View>()
    private val pickTargets = java.util.WeakHashMap<Activity, String>()

    private fun installPickBall(activity: Activity, pkg: String) {
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return
        if (floatingBalls[activity] != null) return
        // 只有悬浮窗模式才显示球
        if (loadPickMode(activity) != PICK_MODE_BALL) return
        // 用户长按关闭过就不显示
        if (java.io.File(activity.filesDir, BALL_OFF_FILE).exists()) return
        pickTargets[activity] = pkg
        val ball = createPickBall(activity, pkg)
        decor.addView(ball, ballLayoutParams(activity))
        floatingBalls[activity] = ball
        logFramework("自用模块: 拦截悬浮球已添加（$pkg）")
    }

    /** 拦截方式：ball = 悬浮球点选；auto = 弹窗出现时自动询问。 */
    private fun loadPickMode(context: android.content.Context): String = try {
        val file = java.io.File(context.filesDir, PICK_MODE_FILE)
        if (!file.exists()) PICK_MODE_BALL else file.readText().trim().ifBlank { PICK_MODE_BALL }
    } catch (_: Throwable) {
        PICK_MODE_BALL
    }

    /** 把球挂到弹窗自己的窗口上，保证它在弹窗之上（可点）；同时隐藏 Activity 上的球。 */
    private fun attachBallToDialog(dialog: android.app.Dialog, pkg: String) {
        try {
            val decor = dialog.window?.decorView as? android.view.ViewGroup ?: return
            if (decor.findViewWithTag<android.view.View>(BALL_TAG) != null) return
            val context = dialog.context ?: return
            // 隐藏 Activity 上的球，避免出现两个
            synchronized(floatingBalls) {
                floatingBalls.values.forEach { it.visibility = android.view.View.GONE }
                logFramework("自用模块: 已隐藏 ${floatingBalls.size} 个 Activity 悬浮球")
            }
            val ball = createPickBall(context, pkg)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val params = android.widget.FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = dp(8)
                rightMargin = dp(8)
            }
            decor.addView(ball, params)
            logFramework("自用模块: 悬浮球已挂到弹窗上层")
        } catch (_: Throwable) {
        }
    }

    /** 弹窗关闭后恢复 Activity 上的球（仅当没有别的弹窗还在显示）。 */
    private fun restoreActivityBalls() {
        try {
            val stillShowing = synchronized(shownDialogs) {
                shownDialogs.any { it.get()?.isShowing == true }
            }
            if (stillShowing) return
            synchronized(floatingBalls) {
                floatingBalls.values.forEach { it.visibility = android.view.View.VISIBLE }
            }
            logFramework("自用模块: 恢复 Activity 悬浮球")
        } catch (_: Throwable) {
        }
    }

    /** 在内容容器里找"真正的卡片"（有背景、面积最大的子 View）。 */
    private fun findCardView(content: android.view.ViewGroup): android.view.View {
        var best: android.view.View? = null
        var bestArea = 0
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (!child.isShown || child.width <= 0 || child.height <= 0) continue
            val area = child.width * child.height
            if (child.background != null && area > bestArea) {
                best = child
                bestArea = area
            }
        }
        return best ?: content
    }

    private fun ballLayoutParams(context: android.content.Context): android.widget.FrameLayout.LayoutParams {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        return android.widget.FrameLayout.LayoutParams(dp(46), dp(46)).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            // 离屏幕边缘留出距离，避免被返回手势吃掉点击
            rightMargin = dp(36)
        }
    }

    private fun createPickBall(context: android.content.Context, pkg: String): android.widget.TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val ball = android.widget.TextView(context).apply {
            text = "拦"
            tag = BALL_TAG
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(200, 103, 80, 164))
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
            elevation = dp(4).toFloat()
            isClickable = true
            isFocusable = false
        }

        var downX = 0f
        var downY = 0f
        var startMargin = 0
        var startTop = 0
        var dragging = false
        var longPressed = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val longPressRunnable = Runnable {
            longPressed = true
            try {
                java.io.File(context.filesDir, BALL_OFF_FILE).writeText("1")
                ball.visibility = android.view.View.GONE
                android.widget.Toast.makeText(context, "悬浮球已关闭，可在模块里重新打开", android.widget.Toast.LENGTH_SHORT).show()
                logFramework("自用模块: 悬浮球已由长按关闭")
            } catch (_: Throwable) {
            }
        }
        ball.setOnTouchListener { view, event ->
            val lp = view.layoutParams as? android.widget.FrameLayout.LayoutParams
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    longPressed = false
                    if (lp != null) {
                        startMargin = lp.rightMargin
                        startTop = lp.topMargin
                    }
                    handler.postDelayed(longPressRunnable, 700L)
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging && lp != null) {
                        lp.rightMargin = (startMargin - dx.toInt()).coerceAtLeast(0)
                        lp.topMargin = (startTop + dy.toInt()).coerceAtLeast(0)
                        view.layoutParams = lp
                    }
                    true
                }

                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!dragging && !longPressed) startPicking(context, pkg)
                    true
                }

                else -> false
            }
        }
        return ball
    }

    /** 进入拾取模式：下一次触摸弹窗时拾取它。 */
    private fun startPicking(context: android.content.Context, pkg: String) {
        picking = true
        highlightShownDialogs(context, true)
        try {
            android.widget.Toast.makeText(context, "请点击要拦截的弹窗", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
        logFramework("自用模块: 进入拾取模式（$pkg）")
    }

    /** 拾取模式下给已显示的弹窗描边（只框内容，不框整个透明窗口）。 */
    private fun highlightShownDialogs(context: android.content.Context, on: Boolean) {
        try {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            var applied = 0
            synchronized(shownDialogs) {
                shownDialogs.forEach { ref ->
                    try {
                        val dialog = ref.get() ?: return@forEach
                        val decor = dialog.window?.decorView as? android.view.ViewGroup ?: return@forEach
                        if (!on) {
                            decor.findViewWithTag<android.view.View>(HIGHLIGHT_TAG)?.let {
                                decor.removeView(it)
                            }
                            return@forEach
                        }
                        // 真正的内容视图：decorView 一般只有一个子 View（content 容器）
                        val content = decor.getChildAt(0) as? android.view.ViewGroup ?: return@forEach
                        val card = findCardView(content)
                        val cardLoc = IntArray(2)
                        val decorLoc = IntArray(2)
                        card.getLocationInWindow(cardLoc)
                        decor.getLocationInWindow(decorLoc)
                        val overlay = android.view.View(context).apply {
                            tag = HIGHLIGHT_TAG
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setStroke(dp(4), android.graphics.Color.argb(255, 103, 80, 164))
                                cornerRadius = dp(10).toFloat()
                            }
                        }
                        val params = android.widget.FrameLayout.LayoutParams(
                            card.width, card.height
                        ).apply {
                            leftMargin = cardLoc[0] - decorLoc[0]
                            topMargin = cardLoc[1] - decorLoc[1]
                        }
                        decor.addView(overlay, params)
                        applied++
                    } catch (_: Throwable) {
                    }
                }
            }
            logFramework("自用模块: 弹窗描边 ${if (on) "开启" else "关闭"}，命中 $applied 个")
        } catch (_: Throwable) {
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (!shouldHook(pkg)) return
        logFramework("自用模块: 正在注入 $pkg")

        try {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callActivityOnResume",
                Activity::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.args.firstOrNull() as? Activity ?: return
                        if (activity.packageName != pkg) return
                        if (activity.isFinishing || activity.isDestroyed) return
                        try {
                            onActivityResumed(activity, pkg)
                        } catch (t: Throwable) {
                            logFramework("自用模块: 处理 $pkg 失败：${describe(t)}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logFramework("自用模块: 在 $pkg 安装 Hook 失败：${describe(t)}")
        }

        // 通用弹窗守卫：不依赖配置，第一次弹窗询问、之后按规则执行。
        installDialogGuard(pkg, lpparam.classLoader)
        // Toast 守卫：命中关键字时拦掉目标应用自己弹的 Toast
        installToastGuard(pkg)
        // 版本伪装：在包加载阶段就装好，避免应用在 Application.onCreate 里检查更新时漏掉
        installVersionSpoofEarly(pkg, lpparam.classLoader)
    }

    /** 拦截目标应用自己弹的 Toast（系统/其他应用的 Toast 拦不到）。 */
    private fun installToastGuard(pkg: String) {
        try {
            XposedHelpers.findAndHookMethod(
                android.widget.Toast::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (showingConfirm) return
                        val toast = param.thisObject as? android.widget.Toast ?: return
                        try {
                            // Android 12+ 的 Toast.getView() 返回 null（系统统一渲染），
                            // 所以不能用 view.context 拿 context，走当前应用的 Application。
                            val context = currentApp() ?: return
                            if (!Features.isOn(context, Features.MASTER)) return
                            if (!Features.isOn(context, Features.TOAST)) return
                            val text = toastText(toast) ?: return
                            if (text.isBlank()) return
                            val keywords = com.example.selfxposed.Keywords.load(context)
                            val hit = keywords.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
                            if (hit) {
                                param.result = null
                                logFramework("自用模块: 已拦截 Toast「${text.take(30)}」")
                            } else {
                                // 方便核对：把实际文字和当前关键字都记下来
                                logFramework(
                                    "自用模块: Toast 未命中「${text.take(40)}」关键字=${keywords.joinToString("/")}"
                                )
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            logFramework("自用模块: $pkg Toast 守卫已安装")
        } catch (t: Throwable) {
            logFramework("自用模块: $pkg Toast 守卫安装失败：${describe(t)}")
        }
    }

    /** 取当前进程的 Application（Toast 本身拿不到 context）。 */
    private fun currentApp(): android.app.Application? = try {
        val cls = Class.forName("android.app.ActivityThread")
        val current = XposedHelpers.callStaticMethod(cls, "currentApplication")
        current as? android.app.Application
    } catch (_: Throwable) {
        null
    }

    /** 取 Toast 的文字：反射 mText（不依赖 view）。 */
    private fun toastText(toast: android.widget.Toast): String? {
        try {
            var cls: Class<*>? = toast.javaClass
            while (cls != null && cls != android.widget.Toast::class.java) {
                cls = cls.superclass
            }
            val target = cls ?: toast.javaClass
            val field = target.getDeclaredField("mText")
            field.isAccessible = true
            val value = field.get(toast) as? CharSequence
            if (!value.isNullOrBlank()) return value.toString()
        } catch (_: Throwable) {
        }
        // 兜底：自定义 View 的 Toast
        try {
            val view = toast.view ?: return null
            val texts = ArrayList<String>()
            collectTexts(view, texts, 0)
            return texts.firstOrNull { it.isNotBlank() }
        } catch (_: Throwable) {
            return null
        }
    }

    private fun onActivityResumed(activity: Activity, pkg: String) {
        val resolver = activity.contentResolver

        // 0. 本地运行标记：targetSdk 高的应用无法回报状态（包可见性），
        //    模块 UI 有 root/Shizuku 时读这个文件判断"Hook 是否跑过"。
        try {
            java.io.File(activity.filesDir, "selfxposed_seen")
                .writeText(System.currentTimeMillis().toString())
            logFramework("自用模块: 已写入运行标记（$pkg）")
        } catch (t: Throwable) {
            logFramework("自用模块: 写运行标记失败：${describe(t)}")
        }

        // 悬浮球：总开关 + 「拦截弹窗」功能都开启才显示
        try {
            if (Features.isOn(activity, Features.MASTER) && Features.isOn(activity, Features.BLOCK)) {
                installPickBall(activity, pkg)
            }
        } catch (t: Throwable) {
            logFramework("自用模块: 悬浮球添加失败：${describe(t)}")
        }

        // 1. 记录一次运行时握手，供模块首页显示“已连接”。
        try {
            ConfigClient.call(resolver, ConfigClient.M_RECORD_HOOK, pkg)
        } catch (t: Throwable) {
            logFramework("自用模块: 记录 Hook 状态失败，$pkg：${describe(t)}")
        }

        // 2. 读取该应用的配置；读取失败时只提示一次，避免刷屏。
        val loaded = readConfig(activity, pkg) ?: run {
            logOnce(failedLogged, pkg) { writeLog(activity, pkg, "读取配置失败：模块与本应用无法通信") }
            return
        }
        val cfg = loaded.cfg

        // 功能开关：默认关闭。优先看目标应用本地的 features 文件；
        // 非 Root 模式下写不进去，所以再回退到模块配置（只对 targetSdk < 30 的应用读得到）。
        val popupOn = Features.isOn(activity, Features.MASTER) &&
            (Features.isOn(activity, Features.POPUP) || cfg.optBoolean("popupEnabled", false))
        if (!popupOn) {
            logOnce(disabledLogged, pkg) { writeLog(activity, pkg, "Hook 已触发，但「弹窗提醒」功能未启用") }
            logFramework("自用模块: $pkg 弹窗提醒未启用")
            return
        }

        val text = cfg.optString("popupText", "你好").ifBlank { "你好" }
        val showMs = cfg.optInt("showMs", 5000)
        val fadeMs = cfg.optInt("fadeMs", 1000)

        // 启动页（Splash / 欢迎页）会在 1~2 秒后被主界面替换。
        // 在它上面挂弹窗会因为窗口销毁而“闪一下再出现”，所以直接跳过，等真正的界面。
        if (isTransientActivity(activity)) {
            logFramework("自用模块: $pkg 跳过启动页 ${activity.javaClass.simpleName}")
            return
        }

        // 已经显示中的弹窗：跟随到新的 Activity，保留剩余时间，不重置计时。
        if (PopupController.isShowing(pkg)) {
            PopupController.show(activity, pkg, text, showMs, fadeMs)
            return
        }

        // 刚发生过 Activity 切换时，把待显示任务改挂到当前 Activity 上。
        pendingShows.remove(pkg)?.let { pendingHandler.removeCallbacks(it) }

        val now = SystemClock.uptimeMillis()
        val last = lastShownAt[pkg] ?: 0L
        // 冷却时间覆盖一次完整展示，避免快速切换 Activity 时连续重弹。
        val cooldown = maxOf(4_000L, showMs.toLong() + fadeMs.toLong() + 1_500L)
        if (now - last < cooldown) return

        lastShownAt[pkg] = now
        scheduleShow(activity, pkg, text, showMs, fadeMs)
    }

    /**
     * 延迟一小段时间再真正挂载弹窗：如果这期间界面又被替换（常见于启动页 → 主界面），
     * 新的 resume 会取消这次任务并重新计时，最终只会在稳定下来的界面上弹一次。
     */
    private fun scheduleShow(activity: Activity, pkg: String, text: String, showMs: Int, fadeMs: Int) {
        val runnable = Runnable {
            pendingShows.remove(pkg)
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            val attached = PopupController.show(activity, pkg, text, showMs, fadeMs)
            writeLog(
                activity, pkg,
                "弹窗 attached=$attached activity=${activity.javaClass.simpleName} showMs=$showMs fadeMs=$fadeMs"
            )
            logFramework("自用模块: $pkg 显示弹窗 attached=$attached showMs=$showMs fadeMs=$fadeMs")
            logOnce(detectedLogged, pkg) { writeLog(activity, pkg, "已在 $pkg 显示弹窗") }
        }
        pendingShows[pkg] = runnable
        pendingHandler.postDelayed(runnable, SETTLE_DELAY_MS)
    }

    /**
     * 版本伪装：让目标应用查到的"自己"是更高的版本，从而不再弹强制更新。
     *
     * 配置必须存在目标应用本地（`files/selfxposed_update_block.conf`），
     * 因为 targetSdk 30+ 的应用调不到模块 Provider。
     * 只影响「查询本应用包」的返回值，不改系统里真实的包信息。
     */
    private fun installUpdateBlockHooks(activity: Activity, pkg: String, cfg: JSONObject) {
        if (!Features.isOn(activity, Features.UPDATE)) return
        val conf = readUpdateBlockConf(activity)
        val fakeName = conf.first.trim()
        val fakeCode = conf.second
        if (fakeName.isEmpty() && fakeCode <= 0) return

        val loader = activity.javaClass.classLoader
        val versionHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val queried = param.args.firstOrNull() as? String ?: return
                    if (queried != pkg) return
                    val info = param.result as? android.content.pm.PackageInfo ?: return
                    if (fakeName.isNotEmpty()) info.versionName = fakeName
                    if (fakeCode > 0) {
                        @Suppress("DEPRECATION")
                        info.versionCode = fakeCode
                    }
                } catch (_: Throwable) {
                }
            }
        }

        // getPackageInfo(String, int) —— 所有版本都有
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                loader,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                versionHook
            )
            logFramework("自用模块: $pkg 版本伪装已安装 $fakeName($fakeCode)")
        } catch (t: Throwable) {
            logFramework("自用模块: $pkg 版本伪装安装失败：${describe(t)}")
        }

        // getPackageInfo(String, PackageInfoFlags) —— Android 13+ 的另一个重载
        try {
            val flagsClass = Class.forName("android.content.pm.PackageManager\$PackageInfoFlags")
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                loader,
                "getPackageInfo",
                String::class.java,
                flagsClass,
                versionHook
            )
            logFramework("自用模块: $pkg 版本伪装（PackageInfoFlags）已安装")
        } catch (_: Throwable) {
            // 低版本没有这个重载，忽略
        }
    }

    /**
     * 版本伪装（包加载阶段就安装）：应用可能在 Application.onCreate 里就检查更新，
     * 等到 Activity onResume 再装 hook 会漏掉这一次。
     * 这里无条件装 hook，运行时再读配置和开关决定是否伪装。
     */
    private fun installVersionSpoofEarly(pkg: String, loader: ClassLoader?) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val queried = param.args.firstOrNull() as? String ?: return
                    val info = param.result as? android.content.pm.PackageInfo ?: return
                    val app = android.app.AndroidAppHelper.currentApplication()
                    if (app == null) {
                        logVersionSpoofOnce("$pkg-app-null", "版本伪装：currentApplication 为 null，跳过")
                        return
                    }
                    // 只伪装「宿主进程自己的包」：同一进程里可能被注入多个包
                    // （如 WebView / 系统组件），绝不能把它们也改掉。
                    val hostPkg = app.packageName
                    if (queried != hostPkg) return
                    if (!Features.isOn(app, Features.UPDATE)) {
                        logVersionSpoofOnce("$pkg-off", "版本伪装：功能开关未开启")
                        return
                    }
                    val (fakeName, fakeCode) = cachedUpdateConf(app)
                    if (fakeName.isEmpty() && fakeCode <= 0) {
                        logVersionSpoofOnce("$pkg-empty", "版本伪装：配置为空")
                        return
                    }
                    if (fakeName.isNotEmpty()) info.versionName = fakeName
                    if (fakeCode > 0) {
                        @Suppress("DEPRECATION")
                        info.versionCode = fakeCode
                    }
                    logVersionSpoofOnce(
                        "$pkg-hit-$fakeName-$fakeCode",
                        "版本伪装命中：$queried → $fakeName($fakeCode)"
                    )
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                loader,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                hook
            )
        } catch (_: Throwable) {
        }
        try {
            val flagsClass = Class.forName("android.content.pm.PackageManager\$PackageInfoFlags")
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                loader,
                "getPackageInfo",
                String::class.java,
                flagsClass,
                hook
            )
        } catch (_: Throwable) {
        }
        // getPackageInfoAsUser（隐藏 API，部分 ROM / 应用会走它）
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                loader,
                "getPackageInfoAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                hook
            )
        } catch (_: Throwable) {
        }
        // getInstalledPackages：遍历列表里找到自己时也伪装
        val installedHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val list = param.result as? List<*> ?: return
                    val app = android.app.AndroidAppHelper.currentApplication() ?: return
                    if (!Features.isOn(app, Features.UPDATE)) return
                    val hostPkg = app.packageName
                    val (fakeName, fakeCode) = cachedUpdateConf(app)
                    if (fakeName.isEmpty() && fakeCode <= 0) return
                    @Suppress("UNCHECKED_CAST")
                    val infos = list as? MutableList<Any?>
                    infos?.forEachIndexed { index, item ->
                        val info = item as? android.content.pm.PackageInfo ?: return@forEachIndexed
                        if (info.packageName != hostPkg) return@forEachIndexed
                        if (fakeName.isNotEmpty()) info.versionName = fakeName
                        if (fakeCode > 0) {
                            @Suppress("DEPRECATION")
                            info.versionCode = fakeCode
                        }
                        infos[index] = info
                    }
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                loader,
                "getInstalledPackages",
                Int::class.javaPrimitiveType,
                installedHook
            )
        } catch (_: Throwable) {
        }
        logFramework("自用模块: $pkg 版本伪装 hook 已在包加载阶段安装")
    }

    /** 版本伪装调试日志：同一个 key 每进程只打一次，避免刷屏。 */
    private fun logVersionSpoofOnce(key: String, message: String) {
        if (versionLogOnce.add(key)) logFramework("自用模块: $message")
    }

    /** 3 秒内复用同一次文件读取，避免每次查版本都读盘。 */
    private fun cachedUpdateConf(context: android.content.Context): Pair<String, Int> {
        val now = System.currentTimeMillis()
        val cached = updateConfCache
        if (cached != null && now - updateConfAt < 3_000L) return cached
        val fresh = readUpdateBlockConf(context)
        updateConfCache = fresh
        updateConfAt = now
        return fresh
    }

    /** 读目标应用本地的版本伪装配置：返回 (fakeName, fakeCode)。 */
    private fun readUpdateBlockConf(context: android.content.Context): Pair<String, Int> {
        return try {
            val file = java.io.File(context.filesDir, "selfxposed_update_block.conf")
            if (!file.exists()) return "" to 0
            var name = ""
            var code = 0
            file.readLines().forEach { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@forEach
                when (line.substring(0, index).trim()) {
                    "fakeName" -> name = line.substring(index + 1).trim()
                    "fakeCode" -> code = line.substring(index + 1).trim().toIntOrNull() ?: 0
                }
            }
            name to code
        } catch (_: Throwable) {
            "" to 0
        }
    }

    /**
     * 通用弹窗守门员：不需要跨进程配置。
     *
     * 规则存在目标应用自己的 SharedPreferences 里（hook 有写权限），所以
     * targetSdk 35 的应用也能用。第一次遇到弹窗只询问、不拦截；用户选择后写入规则。
     */
    private fun installDialogGuard(pkg: String, loader: ClassLoader?) {
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Dialog::class.java,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (showingConfirm) return
                        val dialog = param.thisObject as? android.app.Dialog ?: return
                        try {
                            val context = dialog.context ?: return
                            if (!Features.isOn(context, Features.MASTER)) return
                            if (!Features.isOn(context, Features.BLOCK)) return
                            // 自动拦截：先把窗口设透明，布局照常进行，用户看不到它弹出来
                            if (loadBlockRules(context).mode == "block") {
                                dialog.window?.let { window ->
                                    val attributes = window.attributes
                                    attributes.alpha = 0f
                                    window.attributes = attributes
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dialog = param.thisObject as? android.app.Dialog ?: return
                        if (showingConfirm) return
                        try {
                            val context = dialog.context ?: return
                            if (!Features.isOn(context, Features.MASTER)) return
                            if (!Features.isOn(context, Features.BLOCK)) return
                            val mode = loadBlockRules(context).mode
                            if (mode == "allow") return
                            // show() 之后布局才完成，延迟一点再读标题、截图、再决定是否拦截。
                            dialog.window?.decorView?.postDelayed({
                                try {
                                    evaluateShownDialog(pkg, dialog)
                                } catch (_: Throwable) {
                                }
                            }, 150)
                        } catch (t: Throwable) {
                            logFramework("自用模块: 弹窗观察异常：${describe(t)}")
                        }
                    }
                }
            )
            // 拾取模式：用户点弹窗时截获，识别这个弹窗并给出作用域
            XposedHelpers.findAndHookMethod(
                android.app.Dialog::class.java,
                "dismiss",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        restoreActivityBalls()
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                android.app.Dialog::class.java,
                "dispatchTouchEvent",
                android.view.MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!picking) return
                        val event = param.args.firstOrNull() as? android.view.MotionEvent ?: return
                        if (event.actionMasked != android.view.MotionEvent.ACTION_UP) {
                            param.result = true
                            return
                        }
                        picking = false
                        param.result = true
                        val dialog = param.thisObject as? android.app.Dialog ?: return
                        try {
                            handlePickedDialog(dialog)
                        } catch (t: Throwable) {
                            logFramework("自用模块: 拾取处理失败：${describe(t)}")
                        }
                    }
                }
            )
            logFramework("自用模块: $pkg 通用弹窗守卫已安装")
        } catch (t: Throwable) {
            logFramework("自用模块: $pkg 通用弹窗守卫安装失败：${describe(t)}")
        }
    }

    /** 用户点选了一个弹窗：显示它的作用域信息，并让用户确认拦截。 */
    private fun handlePickedDialog(dialog: android.app.Dialog) {
        val context = dialog.context ?: return
        highlightShownDialogs(context, false)
        val texts = ArrayList<String>()
        try {
            dialog.window?.decorView?.let { collectTexts(it, texts, 0) }
        } catch (_: Throwable) {
        }
        val title = texts.firstOrNull { it.length in 1..60 } ?: ""
        val className = dialog.javaClass.name.substringAfterLast('.')
        val activityName = (context as? Activity)?.javaClass?.name?.substringAfterLast('.') ?: ""
        val rect = android.graphics.Rect()
        try {
            dialog.window?.decorView?.getGlobalVisibleRect(rect)
        } catch (_: Throwable) {
        }
        logFramework("自用模块: 拾取到弹窗 title=$title class=$className activity=$activityName")
        showPickResult(context, dialog, title, className, activityName, rect)
    }

    /** 底部信息条：显示弹窗作用域，两个按钮「拦截这个 / 取消」。 */
    private fun showPickResult(
        context: android.content.Context,
        dialog: android.app.Dialog,
        title: String,
        className: String,
        activityName: String,
        rect: android.graphics.Rect
    ) {
        try {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val body = android.widget.TextView(context).apply {
                text = "检测到弹窗\n标题：${title.ifBlank { "无标题" }}\n类型：$className" +
                    (if (activityName.isNotBlank()) " · 界面：$activityName" else "") +
                    "\n位置：[${rect.left},${rect.top}]-[${rect.right},${rect.bottom}]"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(16), dp(12), dp(16), dp(6))
            }
            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(dp(12), 0, dp(12), dp(10))
            }
            val cancel = android.widget.TextView(context).apply {
                text = "取消"
                setTextColor(android.graphics.Color.argb(200, 200, 200, 210))
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { dismissParent(this) }
            }
            val confirm = android.widget.TextView(context).apply {
                text = "拦截这个"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(255, 103, 80, 164))
                    cornerRadius = dp(16).toFloat()
                }
            }
            row.addView(cancel)
            row.addView(confirm, android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(8)
            })

            val card = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(235, 28, 27, 31))
                    cornerRadius = dp(16).toFloat()
                }
                addView(body)
                addView(row)
            }

            val picker = android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
            val wrapper = android.widget.FrameLayout(context).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(card, android.widget.FrameLayout.LayoutParams(-1, -2).apply {
                    gravity = android.view.Gravity.BOTTOM
                })
            }
            picker.setContentView(wrapper, android.view.ViewGroup.LayoutParams(-1, -1))
            picker.window?.apply {
                setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                )
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                setLayout(-1, -1)
            }
            showingConfirm = true
            picker.show()
            showingConfirm = false

            confirm.setOnClickListener {
                try {
                    saveBlockRules(context, "titles", title.ifBlank { className })
                    dialog.dismissQuietly()
                    logFramework("自用模块: 已按拾取结果拦截「${title.ifBlank { className }}」")
                } catch (_: Throwable) {
                }
                picker.dismiss()
            }
            cancel.setOnClickListener { picker.dismiss() }
        } catch (t: Throwable) {
            showingConfirm = false
            logFramework("自用模块: 拾取信息条显示失败：${describe(t)}")
        }
    }

    private fun dismissParent(view: android.view.View) {
        try {
            var parent = view.parent
            while (parent != null) {
                if (parent is android.app.Dialog) {
                    parent.dismiss()
                    return
                }
                parent = parent.parent
            }
        } catch (_: Throwable) {
        }
    }

    /** 弹窗已经显示后再处理：读标题、按规则/关键字拦或询问。 */
    private fun evaluateShownDialog(pkg: String, dialog: android.app.Dialog) {
        if (showingConfirm) return
        synchronized(shownDialogs) {
            shownDialogs.add(java.lang.ref.WeakReference(dialog))
        }
        attachLongPress(dialog, pkg)
        attachBallToDialog(dialog, pkg)
        val context = dialog.context ?: return
        val rules = loadBlockRules(context)

        val texts = ArrayList<String>()
        try {
            dialog.window?.decorView?.let { collectTexts(it, texts, 0) }
        } catch (_: Throwable) {
        }
        val joined = texts.joinToString(" ")
        val title = texts.firstOrNull { it.length in 1..60 }

        // 1. 自定义关键字（命中标题或任意文本就拦）
        val keywords = com.example.selfxposed.Keywords.load(context)
        if (keywords.isNotEmpty() && keywords.any { it.isNotBlank() && joined.contains(it, ignoreCase = true) }) {
            captureScreenshot(context, dialog, title)
            dialog.dismissQuietly()
            logFramework("自用模块: 关键字命中，已拦截弹窗")
            if (loadPickMode(context) == PICK_MODE_AUTO) showBlockedHint(context, pkg)
            return
        }

        // 2. 已学习的规则
        val ruleHit = rules.mode == "block" || (rules.mode == "titles" && title != null &&
            rules.titles.any { it.isNotBlank() && (title.contains(it) || it.contains(title)) })
        if (ruleHit) {
            captureScreenshot(context, dialog, title)
            dialog.dismissQuietly()
            logFramework("自用模块: 已关闭弹窗「${title ?: "无标题"}」（规则=${rules.mode}）")
            if (loadPickMode(context) == PICK_MODE_AUTO) showBlockedHint(context, pkg)
            return
        }

        // 没有命中规则时：自动模式弹询问框，悬浮球模式什么都不做
        if (rules.mode.isEmpty() &&
            loadPickMode(context) == PICK_MODE_AUTO &&
            askedThisProcess.add(pkg)
        ) {
            showConfirmCard(context, pkg, dialog, title)
        }
    }

    /** 自动模式：弹窗出现后询问是否拦截。 */
    private fun showConfirmCard(
        context: android.content.Context,
        pkg: String,
        source: android.app.Dialog,
        title: String?
    ) {
        showingConfirm = true
        try {
            val message = if (title.isNullOrBlank()) {
                "检测到一个弹窗，要拦截它吗？"
            } else {
                "检测到弹窗：\n「$title」\n\n要拦截它吗？"
            }
            android.app.AlertDialog.Builder(context)
                .setTitle("自用模块")
                .setMessage(message)
                .setPositiveButton("全部拦截") { _, _ ->
                    saveBlockRules(context, "block", null)
                    dismissAllShown()
                }
                .setNeutralButton("只拦这个") { _, _ ->
                    saveBlockRules(context, "titles", title)
                    dismissAllShown()
                }
                .setNegativeButton("不拦") { _, _ ->
                    saveBlockRules(context, "allow", null)
                }
                .setOnDismissListener { showingConfirm = false }
                .show()
            logFramework("自用模块: 自动模式确认卡已显示")
        } catch (t: Throwable) {
            logFramework("自用模块: 确认卡显示失败：${describe(t)}")
            showingConfirm = false
        }
    }

    /**
     * 把弹窗画成图片存到目标应用自己的目录，供模块 UI 查看。
     * 同一个弹窗（内容相同）在短时间内被 show 多次时只保存一张。
     */
    private fun captureScreenshot(
        context: android.content.Context,
        dialog: android.app.Dialog,
        title: String?
    ) {
        try {
            val decor = dialog.window?.decorView ?: return
            val width = decor.width
            val height = decor.height
            if (width <= 0 || height <= 0) return
            val bitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888
            )
            decor.draw(android.graphics.Canvas(bitmap))

            val buffer = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, buffer)
            bitmap.recycle()
            val bytes = buffer.toByteArray()

            // 内容去重：同一张图 5 秒内不重复保存
            val hash = bytes.contentHashCode()
            val now = System.currentTimeMillis()
            if (hash == lastShotHash && now - lastShotAt < SHOT_DEDUP_MS) {
                logFramework("自用模块: 相同弹窗已在 ${now - lastShotAt}ms 内截过，跳过")
                return
            }
            lastShotHash = hash
            lastShotAt = now

            val dir = java.io.File(context.filesDir, SHOT_DIR).apply { mkdirs() }
            // 同一个标题只保留最新一张，避免同一弹窗每次启动都累积
            if (!title.isNullOrBlank()) {
                dir.listFiles { file -> file.isFile && file.name.endsWith(".txt") }?.forEach { txt ->
                    val old = try {
                        txt.readText()
                    } catch (_: Throwable) {
                        ""
                    }
                    if (old == title) {
                        java.io.File(dir, txt.name.removeSuffix(".txt") + ".png").delete()
                        txt.delete()
                    }
                }
            }
            val base = now.toString()
            java.io.FileOutputStream(java.io.File(dir, "$base.png")).use { out ->
                out.write(bytes)
            }
            if (!title.isNullOrBlank()) {
                java.io.File(dir, "$base.txt").writeText(title)
            }
            pruneScreenshots(dir)
            logFramework("自用模块: 已保存拦截截图 $base（标题=${title ?: "无"}）")
        } catch (t: Throwable) {
            logFramework("自用模块: 截图失败：${describe(t)}")
        }
    }

    private fun pruneScreenshots(dir: java.io.File) {
        try {
            val shots = dir.listFiles { file -> file.isFile && file.name.endsWith(".png") }
                ?.sortedBy { it.name } ?: return
            if (shots.size <= MAX_SHOTS) return
            shots.take(shots.size - MAX_SHOTS).forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    /** 样式优先级：目标应用本地（自定义，root 写入）→ 模块配置（预设，低 targetSdk 免 root）→ 默认。 */
    private fun resolveHintStyle(context: android.content.Context): com.example.selfxposed.HintStyle {
        com.example.selfxposed.HintStyle.fromFile(context)?.let { return it }
        return try {
            val raw = ConfigClient.hintStyle(context.contentResolver)
            com.example.selfxposed.HintStyle.fromJson(
                if (raw.isNullOrBlank()) null else org.json.JSONObject(raw)
            ) ?: com.example.selfxposed.HintStyle.DEFAULT
        } catch (_: Throwable) {
            com.example.selfxposed.HintStyle.DEFAULT
        }
    }

    /** 拦截后给一个可长按的小提示条，避免"被拦了就再也改不了规则"。 */
    private fun showBlockedHint(context: android.content.Context, pkg: String) {
        try {
            val style = resolveHintStyle(context)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val bgColor = try {
                android.graphics.Color.parseColor(style.bgColor)
            } catch (_: Throwable) {
                android.graphics.Color.argb(215, 28, 27, 31)
            }
            val dialog = android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
            val text = android.widget.TextView(context).apply {
                this.text = "已拦截弹窗 · 长按改规则"
                setTextColor(android.graphics.Color.WHITE)
                textSize = style.sizeSp.toFloat()
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dp(16).toFloat()
                }
                isLongClickable = true
                setOnLongClickListener {
                    dialog.dismissQuietly()
                    showRuleMenu(context, pkg)
                    true
                }
            }
            val gravity = when (style.position) {
                "top" -> android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                "center" -> android.view.Gravity.CENTER
                else -> android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            }
            // 窗口只包住提示条本身，并标记 NOT_TOUCH_MODAL：
            // 这样只有提示条这一小块能吃触摸，屏幕其它区域的点击照常传给游戏。
            dialog.setContentView(text)
            dialog.window?.let { window ->
                window.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                )
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0f)
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                window.setGravity(gravity)
            }
            showingConfirm = true
            dialog.show()
            showingConfirm = false
            dialog.window?.let { window ->
                window.setLayout(-2, -2)
                window.setGravity(gravity)
                val params = window.attributes
                if (style.position != "center") params.y = dp(style.offsetDp)
                window.attributes = params
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                dialog.dismissQuietly()
            }, 4000)
        } catch (_: Throwable) {
            showingConfirm = false
        }
    }

    /** 长按弹窗任意位置 → 规则菜单（不依赖 root，随时改）。 */
    private fun attachLongPress(dialog: android.app.Dialog, pkg: String) {
        try {
            val decor = dialog.window?.decorView ?: return
            decor.isLongClickable = true
            decor.setOnLongClickListener {
                showRuleMenu(dialog.context, pkg)
                true
            }
        } catch (_: Throwable) {
        }
    }

    private fun showRuleMenu(context: android.content.Context, pkg: String) {
        showingConfirm = true
        try {
            android.app.AlertDialog.Builder(context)
                .setTitle("自用模块 · 弹窗规则")
                .setItems(arrayOf("全部拦截", "不拦截", "清除规则")) { _, which ->
                    showingConfirm = false
                    when (which) {
                        0 -> {
                            saveBlockRules(context, "block", null)
                            dismissAllShown()
                        }

                        1 -> saveBlockRules(context, "allow", null)
                        2 -> clearBlockRules(context)
                    }
                }
                .setNegativeButton("关闭") { _, _ -> showingConfirm = false }
                .setOnDismissListener { showingConfirm = false }
                .show()
        } catch (_: Throwable) {
            showingConfirm = false
        }
    }

    private fun clearBlockRules(context: android.content.Context) {
        try {
            context.getSharedPreferences(RULES_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
            logFramework("自用模块: 弹窗规则已清除")
        } catch (_: Throwable) {
        }
    }

    /** 关掉当前屏幕上所有已被我们观察到、且仍在显示的弹窗。 */
    private fun dismissAllShown() {
        synchronized(shownDialogs) {
            shownDialogs.forEach { ref ->
                try {
                    ref.get()?.dismissQuietly()
                } catch (_: Throwable) {
                }
            }
            shownDialogs.clear()
        }
    }

    private fun android.app.Dialog.dismissQuietly() {
        try {
            if (isShowing) dismiss()
        } catch (_: Throwable) {
        }
    }

    private data class BlockRules(val mode: String, val titles: Set<String>)

    private fun loadBlockRules(context: android.content.Context): BlockRules {
        val prefs = context.getSharedPreferences(RULES_PREFS, android.content.Context.MODE_PRIVATE)
        @Suppress("DEPRECATION")
        val titles = prefs.getStringSet(KEY_TITLES, null) ?: emptySet()
        return BlockRules(prefs.getString(KEY_MODE, "") ?: "", titles)
    }

    private fun saveBlockRules(context: android.content.Context, mode: String, title: String?) {
        val prefs = context.getSharedPreferences(RULES_PREFS, android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_MODE, mode)
        if (mode == "titles" && !title.isNullOrBlank()) {
            @Suppress("DEPRECATION")
            val titles = HashSet(prefs.getStringSet(KEY_TITLES, null) ?: emptySet())
            titles.add(title)
            editor.putStringSet(KEY_TITLES, titles)
        }
        editor.commit()
        logFramework("自用模块: 弹窗规则已保存 mode=$mode title=$title")
    }

    private fun extractTitle(dialog: android.app.Dialog): String? {
        val decor = try {
            dialog.window?.decorView
        } catch (_: Throwable) {
            null
        } ?: return null
        val texts = ArrayList<String>()
        collectTexts(decor, texts, 0)
        return texts.firstOrNull { it.length in 1..60 }
    }

    private fun isUpdateDialog(dialog: android.app.Dialog): Boolean {
        val decor = try {
            dialog.window?.decorView
        } catch (_: Throwable) {
            null
        } ?: return false
        val texts = ArrayList<String>()
        collectTexts(decor, texts, 0)
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ")
        if (joined.length > 160) return false
        return UPDATE_KEYWORDS.any { joined.contains(it, ignoreCase = true) }
    }

    private fun collectTexts(view: android.view.View, out: MutableList<String>, depth: Int) {
        if (depth > 12) return
        if (view is android.widget.TextView) {
            val text = view.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) out.add(text)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTexts(view.getChildAt(i), out, depth + 1)
            }
        }
    }

    private fun isTransientActivity(activity: Activity): Boolean {
        val name = activity.javaClass.simpleName.lowercase(Locale.ROOT)
        return TRANSIENT_HINTS.any { name.contains(it) }
    }

    private data class LoadedConfig(val cfg: JSONObject, val source: String)

    /**
     * 优先通过 LSPosed 的文件服务直接读模块的 SharedPreferences：
     * 这条路径不需要唤醒模块应用进程，因此国产 ROM 的“自启动”拦截不会影响弹窗。
     * 直读不可用（例如极旧的 LSPosed）时再退回 ContentProvider。
     */
    private fun readConfig(activity: Activity, pkg: String): LoadedConfig? {
        // 0. 首选：通过 LSPosed 文件服务直读模块 prefs 文件。
        //    不受 targetSdk 35 的包可见性限制（那种情况下 ContentResolver 会报 Unknown authority）。
        readModulePrefsFile(pkg)?.let { cfg ->
            logFramework("自用模块: $pkg 配置来自模块文件直读")
            saveLocalCache(activity, pkg, cfg)
            return LoadedConfig(cfg, "file")
        }

        // 1. XSharedPreferences（部分 LSPosed 版本可用）
        val raw = try {
            XSharedPreferences(ConfigClient.MODULE_PACKAGE, ConfigClient.PREFS_NAME)
                .getString(ConfigClient.keyConfig(pkg), null)
        } catch (t: Throwable) {
            logFramework("自用模块: XSharedPreferences 读取失败，$pkg：${describe(t)}")
            null
        }

        if (!raw.isNullOrBlank()) {
            return try {
                val merged = ConfigClient.merge(JSONObject(raw))
                logFramework("自用模块: $pkg 配置来自 XSharedPreferences")
                saveLocalCache(activity, pkg, merged)
                LoadedConfig(merged, "xsp")
            } catch (t: Throwable) {
                logFramework("自用模块: 配置解析失败，$pkg：${describe(t)}")
                null
            }
        }

        // 2. ContentProvider（能读到就用最新的，并刷新本地缓存）
        try {
            val cfg = ConfigClient.config(activity.contentResolver, pkg)
            logFramework("自用模块: $pkg 配置来自 Provider")
            saveLocalCache(activity, pkg, cfg)
            return LoadedConfig(cfg, "provider")
        } catch (t: Throwable) {
            logFramework("自用模块: Provider 读取配置失败，$pkg：${describe(t)}")
        }

        // 3. 兜底：目标应用自己的 SharedPreferences（持久化，重启后仍有效）。
        //    模块进程被划掉/未授权自启动时，仍能用上一次同步到的配置弹窗。
        val cached = loadLocalCache(activity, pkg)
        if (cached != null) {
            logFramework("自用模块: $pkg 配置来自本地缓存")
            return LoadedConfig(cached, "cache")
        }
        return null
    }

    /**
     * 直接读模块的 SharedPreferences XML（走 LSPosed 守护进程的文件服务）。
     * 这是 targetSdk 35 应用唯一能拿到配置的路径：它们看不到模块的 ContentProvider。
     */
    private fun readModulePrefsFile(pkg: String): JSONObject? = try {
        val path = "/data/data/${ConfigClient.MODULE_PACKAGE}/shared_prefs/${ConfigClient.PREFS_NAME}.xml"
        val service = de.robv.android.xposed.SELinuxHelper.getAppDataFileService()
        val stream = service.getFileInputStream(path, 0, 0).stream
        if (stream == null) {
            logFramework("自用模块: 文件服务返回空流（$pkg）")
            null
        } else {
            val text = stream.bufferedReader().use { it.readText() }
            val pattern = Regex(
                "<string name=\"app:${Regex.escape(pkg)}\">(.*?)</string>",
                RegexOption.DOT_MATCHES_ALL
            )
            val raw = pattern.find(text)?.groupValues?.get(1)
            if (raw.isNullOrBlank()) {
                logFramework("自用模块: 模块文件里没有 $pkg 的配置")
                null
            } else {
                val json = raw
                    .replace("&quot;", "\"")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                ConfigClient.merge(JSONObject(json))
            }
        }
    } catch (t: Throwable) {
        logFramework("自用模块: 直读模块文件失败（$pkg）：${describe(t)}")
        null
    }

    private fun saveLocalCache(activity: Activity, pkg: String, cfg: JSONObject) {
        try {
            activity.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().putString("config:$pkg", cfg.toString()).commit()
        } catch (_: Throwable) {
        }
    }

    private fun loadLocalCache(activity: Activity, pkg: String): JSONObject? = try {
        val raw = activity.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString("config:$pkg", null)
        if (raw.isNullOrBlank()) null else ConfigClient.merge(JSONObject(raw))
    } catch (_: Throwable) {
        null
    }

    private fun logOnce(registry: MutableSet<String>, pkg: String, block: () -> Unit) {
        if (registry.add(pkg)) block()
    }

    private fun writeLog(activity: Activity, pkg: String, message: String) {
        try {
            ConfigClient.log(activity.contentResolver, pkg, message)
        } catch (_: Throwable) {
        }
    }

    private fun logFramework(message: String) {
        try {
            XposedBridge.log(message)
        } catch (_: Throwable) {
        }
    }

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"

    private fun shouldHook(pkg: String): Boolean = when {
        pkg == ConfigClient.MODULE_PACKAGE -> false
        pkg == "android" -> false
        pkg.startsWith("android.") -> false
        pkg == "com.android.systemui" -> false
        pkg == "org.lsposed.manager" -> false
        pkg == "de.robv.android.xposed.installer" -> false
        else -> true
    }

    companion object {
        private const val SETTLE_DELAY_MS = 400L
        /** 配置缓存在目标应用自己的 SharedPreferences 里，模块进程不可用时兜底。 */
        private const val CACHE_PREFS = "selfxposed_cache"
        private val TRANSIENT_HINTS =
            listOf("splash", "welcome", "guide", "loading", "launch", "advert", "startup")

        private val detectedLogged = ConcurrentHashMap.newKeySet<String>()
        private val disabledLogged = ConcurrentHashMap.newKeySet<String>()
        private val popupOffLogged = ConcurrentHashMap.newKeySet<String>()
        private val failedLogged = ConcurrentHashMap.newKeySet<String>()
        private val lastShownAt = ConcurrentHashMap<String, Long>()
        private val pendingShows = ConcurrentHashMap<String, Runnable>()
        private val updateHooked = ConcurrentHashMap.newKeySet<String>()
        private val askedThisProcess = ConcurrentHashMap.newKeySet<String>()

        /** 版本伪装配置缓存（3 秒），避免每次查版本都读文件。 */
        @Volatile
        private var updateConfCache: Pair<String, Int>? = null

        @Volatile
        private var updateConfAt = 0L

        private val versionLogOnce = ConcurrentHashMap.newKeySet<String>()

        /** 弹窗规则的存储位置（写在目标应用自己身上，避免跨进程限制）。 */
        private const val RULES_PREFS = "selfxposed_block"
        private const val KEY_MODE = "mode"
        private const val KEY_TITLES = "titles"

        /** 拦截截图存放目录（目标应用自己的 files 下）与保留数量。 */
        private const val SHOT_DIR = "blocked"
        private const val MAX_SHOTS = 20

        /** 同一弹窗内容在这个时间窗内重复出现时不再重复截图。 */
        private const val SHOT_DEDUP_MS = 5_000L

        /** 悬浮球的标记，避免重复挂到同一个窗口。 */
        private const val BALL_TAG = "selfxposed_pick_ball"

        /** 拾取描边的标记。 */
        private const val HIGHLIGHT_TAG = "selfxposed_pick_highlight"

        /** 用户长按关闭悬浮球后写的标记文件。 */
        private const val BALL_OFF_FILE = "selfxposed_ball_off"

        /** 拦截方式配置：ball（悬浮球）/ auto（自动询问）。 */
        private const val PICK_MODE_FILE = "selfxposed_pick_mode.conf"
        private const val PICK_MODE_BALL = "ball"
        private const val PICK_MODE_AUTO = "auto"

        @Volatile
        private var lastShotHash = 0
        @Volatile
        private var lastShotAt = 0L

        /** 正在显示我们自己的确认卡时，跳过守卫，避免递归。 */
        @Volatile
        private var showingConfirm = false

        /** 观察到的、可能仍在显示的弹窗（弱引用，不阻止回收）。 */
        private val shownDialogs =
            java.util.Collections.synchronizedList(ArrayList<java.lang.ref.WeakReference<android.app.Dialog>>())

        /** 更新弹窗的识别关键词：命中任意一个就拦截。 */
        private val UPDATE_KEYWORDS = listOf(
            "发现新版本", "立即更新", "马上更新", "立即升级", "升级到",
            "新版本可用", "版本更新", "update available"
        )

        // 注意：不能在类初始化时 new Handler —— LSPosed 加载模块时主线程 Looper 可能还没就绪。
        private val pendingHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    }
}
