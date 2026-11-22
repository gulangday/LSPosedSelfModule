package com.example.selfxposed

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
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
 * 是否执行功能完全由每个应用自己的配置决定。
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
        // 拦截弹窗只有悬浮窗模式才需要球；净化点选则始终需要球，
        // 否则选了「自动拦截」的用户就找不到净化入口。
        if (!Features.isOn(activity, Features.PURIFY) && loadPickMode(activity) != PICK_MODE_BALL) return
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
                    if (!dragging && !longPressed) onBallTapped(context, pkg)
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

        // 净化：在界面创建阶段就装监听。
        // 只挂在 onResume 的话，第一帧往往已经画完了，用户就会看到"先出现再消失"。
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName != pkg) return
                        if (activity.isFinishing || activity.isDestroyed) return
                        try {
                            applyPurifyRules(activity, pkg)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        // 通用弹窗守卫：不依赖配置，第一次弹窗询问、之后按规则执行。
        installDialogGuard(pkg, lpparam.classLoader)
        // Toast 守卫：命中关键字时拦掉目标应用自己弹的 Toast
        installToastGuard(pkg)
        // 版本伪装：在包加载阶段就装好，避免应用在 Application.onCreate 里检查更新时漏掉
        installVersionSpoofEarly(pkg, lpparam.classLoader)
        // 去广告：拦广告 SDK 的 Activity 跳转（开屏/广告页）与摇一摇传感器
        installAdBlockHooks(pkg, lpparam.classLoader)
        // 运行标记：注入成功就写，比等 Activity 更早，模块据此判断"这次真的注入了"
        installSeenWriter(pkg, lpparam.classLoader)
        // 翻译（方法一）：在文字从资源里被取出来的时候就换掉。
        // 这条不依赖界面用什么框架画，Compose 界面也能翻。
        installTranslateResourceHooks(pkg, lpparam.classLoader)
    }

    /**
     * 翻译（字符串资源层）。
     *
     * 为什么需要这一条：界面上显示的英文，大多来自软件的字符串资源
     * （`Resources.getString` / `getText`）。在文字被取出来的那一瞬间换成中文，
     * 界面用什么框架画的都无所谓——Compose 也只管拿结果去画。
     *
     * 同时做诊断：统计这个应用取了多少次字符串、前几条长什么样。
     * 如果一次都没取过，说明它把文字写死在代码里，这条也救不了。
     */
    private fun installTranslateResourceHooks(pkg: String, loader: ClassLoader?) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    // 翻译默认关闭，而这个 hook 在进程里每次取字符串都会触发：
                    // 先用开关缓存早退，别为没开的翻译付反射取 Application 的成本。
                    if (translateOnCached() == false) return
                    val app = currentApp() ?: return
                    if (!isTranslateOn(app)) return

                    val raw = param.result
                    val text = when (raw) {
                        is String -> raw
                        is CharSequence -> raw.toString()
                        else -> return
                    }
                    if (text.isBlank()) return

                    val index = translateResourceCalls.incrementAndGet()
                    val dict = translateDict(app)
                    // 带危险符号（%s、{0}、<b> 等）的字符串默认不翻，防止把格式串弄坏
                    val translated = TranslateDict.translate(
                        text, dict, translateSkipSymbols(app)
                    )

                    if (translated == null) {
                        translateMiss.incrementAndGet()
                        if (index <= TRANSLATE_DIAG_SAMPLES) {
                            addTranslateSample("✗「${text.take(48)}」没翻（词库没收录）")
                        }
                        return
                    }

                    translateHit.incrementAndGet()
                    if (index <= TRANSLATE_DIAG_SAMPLES) {
                        addTranslateSample("✓「${text.take(48)}」→「${translated.take(48)}」")
                    }
                    param.result = if (raw is String) translated else translated as CharSequence
                } catch (_: Throwable) {
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.content.res.Resources::class.java,
                "getString",
                Int::class.javaPrimitiveType,
                hook
            )
            logFramework("自用模块: $pkg 字符串资源 hook 已安装（getString）")
        } catch (t: Throwable) {
            logFramework("自用模块: $pkg getString hook 失败：${describe(t)}")
        }
        try {
            XposedHelpers.findAndHookMethod(
                android.content.res.Resources::class.java,
                "getText",
                Int::class.javaPrimitiveType,
                hook
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                android.content.res.Resources::class.java,
                "getString",
                Int::class.javaPrimitiveType,
                Array<Any>::class.java,
                hook
            )
        } catch (_: Throwable) {
        }

        // 延迟几秒把结果写进模块日志：能看到原文、译文和没翻出来的词
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val app = currentApp() ?: return@postDelayed
                    if (!isTranslateOn(app)) return@postDelayed
                    val total = translateResourceCalls.get()
                    val hit = translateHit.get()
                    val miss = translateMiss.get()
                    // 带上进程号：应用有多个进程时，每条诊断各算各的，不加进程号会互相矛盾
                    val pid = android.os.Process.myPid()
                    val summary = if (total == 0) {
                        "翻译诊断[进程$pid]：一次都没取过文字，文字写死在代码里，资源层翻译无效"
                    } else {
                        "翻译诊断[进程$pid]：取文字 $total 次，翻成功 $hit 次，没翻成 $miss 次"
                    }
                    writeTranslateLog(app, pkg, summary)
                    val samples = synchronized(translateSamples) { translateSamples.toList() }
                    samples.forEach { writeTranslateLog(app, pkg, "翻译样本：$it") }
                } catch (_: Throwable) {
                }
            }, TRANSLATE_DIAG_DELAY)
        } catch (_: Throwable) {
        }
    }

    /** 记一条诊断样本（上限 [TRANSLATE_DIAG_SAMPLES] 条，避免刷屏）。 */
    private fun addTranslateSample(line: String) {
        try {
            synchronized(translateSamples) {
                if (translateSamples.size < TRANSLATE_DIAG_SAMPLES) translateSamples.add(line)
            }
            logFramework("自用模块: [翻译诊断] $line")
        } catch (_: Throwable) {
        }
    }

    /** 写一条翻译诊断进日志，带「翻译」标记，只出现在翻译页的日志里。 */
    private fun writeTranslateLog(app: android.content.Context, pkg: String, message: String) {
        try {
            ConfigClient.logFeature(app.contentResolver, pkg, FEATURE_TRANSLATE, message)
        } catch (_: Throwable) {
        }
        logFramework("自用模块: $message")
    }

    /** 只读开关缓存：未命中返回 null，由 [isTranslateOn] 读文件后填上。 */
    private fun translateOnCached(): Boolean? {
        val cached = translateFlagCache ?: return null
        return if (System.currentTimeMillis() - translateFlagCacheAt < TRANSLATE_FLAG_TTL) cached else null
    }

    /** 翻译开关：命中缓存就用缓存，避免每次取字符串都读文件。 */
    private fun isTranslateOn(context: android.content.Context): Boolean {
        val now = System.currentTimeMillis()
        val cached = translateFlagCache
        if (cached != null && now - translateFlagCacheAt < TRANSLATE_FLAG_TTL) return cached
        val on = Features.isOn(context, Features.MASTER) && Features.isOn(context, Features.TRANSLATE)
        translateFlagCache = on
        translateFlagCacheAt = now
        return on
    }

    /** 应用进程一创建就写 seen，供模块判断"这次启动有没有被注入"。 */
    private fun installSeenWriter(pkg: String, loader: ClassLoader?) {
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = param.thisObject as? android.app.Application ?: return
                            val file = java.io.File(app.filesDir, "selfxposed_seen")
                            file.writeText(System.currentTimeMillis().toString())
                            logFramework("自用模块: 已写入运行标记（$pkg, Application 创建）")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 去广告（一个开关控制全部）。
     *
     * 只做两件通用的事，不依赖广告 SDK 的具体类名：
     * 1. 拦 Activity 跳转：目标 Activity 类名命中广告 SDK 前缀 → 不启动（开屏、广告落地页）
     * 2. 拦传感器注册：监听器来自广告 SDK → 不注册（摇一摇）
     * 弹窗式广告交给「拦截弹窗」的关键字/规则。
     */
    private fun installAdBlockHooks(pkg: String, loader: ClassLoader?) {
        // 1) 传感器（摇一摇）
        val sensorHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val app = android.app.AndroidAppHelper.currentApplication() ?: return
                    if (!isAdOn(app)) return
                    val listener = param.args.getOrNull(0) ?: return
                    if (!AdSdkBlacklist.matchesInstance(app, listener)) return
                    param.result = null
                    logAdOnce("$pkg-sensor", "去广告：已拦摇一摇传感器（${listener.javaClass.name}）")
                } catch (_: Throwable) {
                }
            }
        }
        listOf(
            arrayOf(android.hardware.SensorEventListener::class.java, android.hardware.Sensor::class.java, Int::class.javaPrimitiveType),
            arrayOf(android.hardware.SensorEventListener::class.java, android.hardware.Sensor::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            arrayOf(android.hardware.SensorEventListener::class.java, android.hardware.Sensor::class.java, Int::class.javaPrimitiveType, android.os.Handler::class.java)
        ).forEach { signature ->
            try {
                XposedHelpers.findAndHookMethod(
                    android.hardware.SensorManager::class.java,
                    "registerListener",
                    *signature,
                    sensorHook
                )
            } catch (_: Throwable) {
            }
        }

        // 2) Activity 跳转（开屏 / 广告落地页）
        val startHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val app = android.app.AndroidAppHelper.currentApplication() ?: return
                    if (!isAdOn(app)) return
                    val intent = param.args.firstOrNull { it is android.content.Intent } as? android.content.Intent
                        ?: return
                    val className = intent.component?.className
                    if (!AdSdkBlacklist.matches(app, className)) return
                    param.result = null
                    logAdOnce("$pkg-start-$className", "去广告：已拦广告页跳转 $className")
                } catch (_: Throwable) {
                }
            }
        }
        // 拦截入口：Activity 自己重写了 startActivity / startActivityForResult，
        // 只挂 ContextWrapper 会漏掉广告 SDK 拿着 Activity 引用发起的大部分跳转。
        installStartActivityHook(android.content.ContextWrapper::class.java, startHook)
        installStartActivityHook(android.app.Activity::class.java, startHook)
        listOf(
            arrayOf<Class<*>>(android.content.Intent::class.java, Int::class.javaPrimitiveType!!),
            arrayOf<Class<*>>(android.content.Intent::class.java, Int::class.javaPrimitiveType!!, android.os.Bundle::class.java)
        ).forEach { signature ->
            try {
                XposedHelpers.findAndHookMethod(
                    android.app.Activity::class.java,
                    "startActivityForResult",
                    *signature,
                    startHook
                )
            } catch (_: Throwable) {
            }
        }
        // 最底层入口：直接走 Instrumentation 的跳转也兜住
        try {
            XposedBridge.hookAllMethods(
                android.app.Instrumentation::class.java,
                "execStartActivity",
                startHook
            )
        } catch (_: Throwable) {
        }
        logFramework("自用模块: $pkg 去广告 hook 已安装")

        // 3) 广告域名拦截：不依赖 SDK 类名，混淆也能拦
        val urlHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val app = android.app.AndroidAppHelper.currentApplication() ?: return
                    if (!isAdOn(app)) return
                    val url = when (val target = param.thisObject) {
                        is java.net.HttpURLConnection -> target.url?.toString()
                        is java.net.URL -> target.toString()
                        else -> null
                    } ?: return
                    if (!AdUrlBlacklist.matches(app, url)) return
                    // HttpURLConnection.getInputStream 返回空流；其余方法返回 null 即可跳过
                    param.result = if (param.method.name == "getInputStream") {
                        java.io.ByteArrayInputStream(ByteArray(0))
                    } else {
                        null
                    }
                    logAdOnce("$pkg-url-${param.method.name}", "去广告：已拦广告域名 $url")
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                java.net.HttpURLConnection::class.java,
                "getInputStream",
                urlHook
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                java.net.URLConnection::class.java,
                "connect",
                urlHook
            )
        } catch (_: Throwable) {
        }
        // WebView 加载广告页（开屏常是 WebView）
        try {
            XposedHelpers.findAndHookMethod(
                android.webkit.WebView::class.java,
                "loadUrl",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val app = android.app.AndroidAppHelper.currentApplication() ?: return
                            if (!isAdOn(app)) return
                            val url = param.args.firstOrNull() as? String ?: return
                            if (!AdUrlBlacklist.matches(app, url)) return
                            param.result = null
                            logAdOnce("$pkg-webview", "去广告：已拦 WebView 广告页 $url")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        // OkHttp 不走 HttpURLConnection，单独在 newCall 处取消命中域名的请求
        installOkHttpAdBlock(pkg, loader)

        // 4) DNS 解析：所有网络库最终都要解析域名，命中黑名单就让它解析失败
        val dnsHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val app = android.app.AndroidAppHelper.currentApplication() ?: return
                    if (!isAdOn(app)) return
                    val host = param.args.firstOrNull() as? String ?: return
                    // 调试：记录所有域名，方便找广告域名
                    if (isAdLogOn(app)) {
                        logAdOnce("$pkg-dnslog-$host", "DNS 请求：$host")
                    }
                    if (!AdUrlBlacklist.matchesHost(app, host)) return
                    // getByName 返回单个地址，getAllByName 返回数组；非法结果会直接崩调用方，故按返回类型区分
                    param.result = if (param.method.name == "getByName") {
                        java.net.InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0))
                    } else {
                        emptyArray<java.net.InetAddress>()
                    }
                    logAdOnce("$pkg-dns-$host", "去广告：已拦广告域名解析 $host")
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedBridge.hookAllMethods(java.net.InetAddress::class.java, "getAllByName", dnsHook)
        } catch (_: Throwable) {
        }
        try {
            XposedBridge.hookAllMethods(java.net.InetAddress::class.java, "getByName", dnsHook)
        } catch (_: Throwable) {
        }
    }

    /** 去广告开关：master 与 AD 双判，1 秒缓存，避免每次网络请求都读文件。 */
    private fun isAdOn(context: android.content.Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = adOnCache
        if (cached != null && now - adOnCacheAt < AD_FLAG_TTL) return cached
        val on = Features.isOn(context, Features.MASTER) && Features.isOn(context, Features.AD)
        adOnCache = on
        adOnCacheAt = now
        return on
    }

    /** 去广告调试开关：同样归属总开关，DNS 回调每次都会问，必须缓存。 */
    private fun isAdLogOn(context: android.content.Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = adLogOnCache
        if (cached != null && now - adLogOnCacheAt < AD_FLAG_TTL) return cached
        val on = Features.isOn(context, Features.MASTER) && Features.isOn(context, Features.AD_LOG)
        adLogOnCache = on
        adLogOnCacheAt = now
        return on
    }

    /** Activity 与 ContextWrapper 的 startActivity 各有多个重载，逐个挂。 */
    private fun installStartActivityHook(target: Class<*>, hook: XC_MethodHook) {
        listOf(
            arrayOf<Class<*>>(android.content.Intent::class.java),
            arrayOf<Class<*>>(android.content.Intent::class.java, android.os.Bundle::class.java)
        ).forEach { signature ->
            try {
                XposedHelpers.findAndHookMethod(target, "startActivity", *signature, hook)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * OkHttp 不走 HttpURLConnection，在 newCall 处直接取消命中域名的请求。
     * 类用运行时反射查找，不引入依赖；目标应用没打包 OkHttp 就跳过。
     */
    private fun installOkHttpAdBlock(pkg: String, loader: ClassLoader?) {
        val clientClass = try {
            XposedHelpers.findClassIfExists("okhttp3.OkHttpClient", loader)
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            XposedBridge.hookAllMethods(
                clientClass,
                "newCall",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = android.app.AndroidAppHelper.currentApplication() ?: return
                            if (!isAdOn(app)) return
                            val call = param.result ?: return
                            val request = XposedHelpers.callMethod(call, "request") ?: return
                            val httpUrl = XposedHelpers.callMethod(request, "url") ?: return
                            val url = httpUrl.toString()
                            if (!AdUrlBlacklist.matches(app, url)) return
                            // 取消比在 enqueue 里抛异常安全：请求不会发出去，回调收到失败
                            XposedHelpers.callMethod(call, "cancel")
                            logAdOnce("$pkg-okhttp-$url", "去广告：已取消 OkHttp 广告请求 $url")
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    /** 去广告日志：同一 key 每进程只打一次；同时写一份进模块日志（带「去广告」标记）。 */
    private fun logAdOnce(key: String, message: String) {
        if (adLogOnce.add(key)) {
            logFramework("自用模块: $message")
            try {
                val app = currentApp()
                if (app != null) {
                    ConfigClient.logFeature(app.contentResolver, app.packageName, FEATURE_AD, message)
                }
            } catch (_: Throwable) {
            }
        }
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

    /** ActivityThread 只需要查找一次，缓存住避免每次取 Application 都做类查找。 */
    private val activityThreadClass: Class<*>? by lazy {
        try {
            Class.forName("android.app.ActivityThread")
        } catch (_: Throwable) {
            null
        }
    }

    /** 取当前进程的 Application（Toast 本身拿不到 context）。 */
    private fun currentApp(): android.app.Application? = try {
        val cls = activityThreadClass ?: return null
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

    // ---- 净化 ----

    /**
     * 按规则处理当前界面上的控件。
     *
     * 界面布局会多次变化（列表加载、广告位后插入），所以延迟扫描几轮并限次，
     * 不做无限监听，避免死循环把目标应用拖卡（对应踩坑 F3 / E8）。
     */
    private fun applyPurifyRules(activity: Activity, pkg: String) {
        if (!Features.isOn(activity, Features.MASTER)) return
        if (!Features.isOn(activity, Features.PURIFY)) return
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return

        // 同一个界面不要重复装监听：onCreate 和 onResume 都会调到这里，
        // 重复注册会让每一帧都多跑几遍扫描，白白拖慢目标应用。
        synchronized(purifyInstalledLock) {
            if (!purifyInstalled.add(activity)) return
        }

        val activityName = activity.javaClass.simpleName

        // 界面存活期间持续扫描：首页/探索/我的常常只是同一个 Activity 换 Fragment，
        // 不会重新走 onCreate，只在创建时扫几轮会漏掉后续页面的广告。
        installPurifyLayoutListener(activity, decor, activityName)

        val rules = purifyRules(activity).filter { it.on }
        if (rules.isEmpty()) return

        // 关键：OnPreDraw 在每一帧真正画出来之前回调。
        // 之前只靠延迟扫描（600ms 后），第一帧早就画完了，所以能看到"先出现再消失"的闪一下。
        // 放在 OnPreDraw 里改 visibility，被改掉的控件这一帧就不会被绘制，彻底不闪。
        try {
            val observer = decor.viewTreeObserver
            var draws = 0
            val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    draws++
                    try {
                        if (isAlive(activity)) applyPurifyRound(activity, activityName, rules)
                    } catch (_: Throwable) {
                    }
                    if (draws >= PURIFY_PRE_DRAW_ROUNDS) {
                        try {
                            if (observer.isAlive) observer.removeOnPreDrawListener(this)
                        } catch (_: Throwable) {
                        }
                    }
                    return true
                }
            }
            observer.addOnPreDrawListener(listener)
        } catch (_: Throwable) {
        }

        // 兜底：首帧之后仍然会有异步插入的广告位/列表内容，再补几轮延迟扫描。
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var round = 0
        val task = object : Runnable {
            override fun run() {
                if (!isAlive(activity)) {
                    synchronized(purifyInstalledLock) { purifyInstalled.remove(activity) }
                    return
                }
                round++
                try {
                    // 重新读规则：运行期间在模块里新加的规则也要能生效
                    val fresh = purifyRules(activity).filter { it.on }
                    if (fresh.isNotEmpty()) applyPurifyRound(activity, activityName, fresh)
                } catch (_: Throwable) {
                }
                if (round < PURIFY_SCAN_ROUNDS) {
                    handler.postDelayed(this, PURIFY_SCAN_DELAY)
                }
            }
        }
        handler.postDelayed(task, PURIFY_SCAN_DELAY)
    }

    /**
     * 监听布局变化，持续套用规则。
     *
     * 用 OnGlobalLayout 而不是每帧的 OnPreDraw：Tab 切 Fragment、列表异步插入广告时
     * 都会触发布局，但不会每帧回调；再加节流避免滚动时反复扫整棵树。
     */
    private fun installPurifyLayoutListener(
        activity: Activity,
        decor: android.view.ViewGroup,
        activityName: String
    ) {
        try {
            val observer = decor.viewTreeObserver
            val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                private var lastAt = 0L

                override fun onGlobalLayout() {
                    try {
                        if (!isAlive(activity)) {
                            try {
                                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                            } catch (_: Throwable) {
                            }
                            synchronized(purifyInstalledLock) { purifyInstalled.remove(activity) }
                            return
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAt < PURIFY_LAYOUT_THROTTLE) return
                        lastAt = now
                        val rules = purifyRules(activity).filter { it.on }
                        if (rules.isNotEmpty()) applyPurifyRound(activity, activityName, rules)
                    } catch (_: Throwable) {
                    }
                }
            }
            observer.addOnGlobalLayoutListener(listener)
        } catch (_: Throwable) {
        }
    }

    /**
     * 规则缓存：2 秒内复用同一次文件读取；过期后先用规则文件的时间戳/长度判断有没有改过，
     * 没改只续期不重读。命中数、跳过原因只影响展示，不影响匹配，所以只看规则文件。
     */
    private fun purifyRules(activity: Activity): List<PurifyRules.Rule> {
        val now = System.currentTimeMillis()
        val cached = purifyCache
        if (cached != null && now - purifyCacheAt < PURIFY_RULES_TTL) return cached
        val file = java.io.File(activity.filesDir, PurifyRules.CONF_FILE)
        val modified = try {
            file.lastModified()
        } catch (_: Throwable) {
            0L
        }
        val length = try {
            file.length()
        } catch (_: Throwable) {
            0L
        }
        if (cached != null && modified == purifyConfModified && length == purifyConfLength) {
            purifyCacheAt = now
            return cached
        }
        val fresh = PurifyRules.loadLocal(activity)
        purifyCache = fresh
        purifyCacheAt = now
        purifyConfModified = modified
        purifyConfLength = length
        return fresh
    }

    /**
     * 一轮扫描：先收集命中的 (控件, 规则)，遍历结束后再统一应用。
     * 之前边遍历边 removeView 会改掉正在遍历的 View 树，容易抛异常甚至把目标应用搞崩。
     */
    private fun applyPurifyRound(
        activity: Activity,
        activityName: String,
        rules: List<PurifyRules.Rule>
    ) {
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return
        val plan = ArrayList<Pair<android.view.View, PurifyRules.Rule>>()

        // 按规则类型预筛：没有对应类型的规则时，就不做资源 ID 查表、取文本这些有成本的事。
        var needId = false
        var needText = false
        var needDesc = false
        var needClass = false
        for (rule in rules) {
            when (rule.type) {
                PurifyRules.TYPE_ID, PurifyRules.TYPE_ID_CONTAINS -> needId = true
                PurifyRules.TYPE_TEXT -> needText = true
                PurifyRules.TYPE_DESC -> needDesc = true
                PurifyRules.TYPE_CLASS -> needClass = true
            }
        }

        walkViews(decor, 0) { view ->
            if (isOwnView(view)) return@walkViews
            val idName = if (needId) idNameOf(view) else ""
            val text = if (needText) {
                (view as? android.widget.TextView)?.text?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val desc = if (needDesc) view.contentDescription?.toString()?.trim().orEmpty() else ""
            val className = if (needClass) view.javaClass.name else ""
            for (rule in rules) {
                if (PurifyRules.matches(rule, activityName, idName, text, desc, className)) {
                    plan.add(view to rule)
                    break
                }
            }
        }

        val decorArea = (decor.width.toLong() * decor.height.toLong()).coerceAtLeast(1L)
        val hits = HashMap<String, Int>()
        // 本轮被黑屏保护跳过的规则及原因
        val skippedNow = HashMap<String, String>()
        // 本轮真正生效（或本来就已生效）的规则，用来清掉之前遗留的跳过标记
        val effectiveNow = HashSet<String>()

        plan.forEach { (view, rule) ->
            try {
                // 收集到应用之间界面可能已经变了，先确认控件还在树上
                if (view.parent == null) return@forEach
                // 防黑屏：隐藏/移除占屏过大的容器会让界面整片空白（实测）。
                // 这条保护对已经保存的旧规则同样生效，不需要用户回去删规则。
                if (rule.action == PurifyRules.ACTION_HIDE || rule.action == PurifyRules.ACTION_REMOVE) {
                    val area = view.width.toLong() * view.height.toLong()
                    val percent = (area * 100L / decorArea).toInt()
                    if (percent >= PURIFY_LARGE_PERCENT) {
                        // 记下原因，让模块里的规则列表能显示"为什么没生效"
                        skippedNow[rule.id] = "占屏 $percent%，已跳过"
                        if (purifySkipLogged.add(rule.id)) {
                            logFramework(
                                "自用模块: 跳过隐藏「${rule.value}」—— 占屏 ${percent}%，隐藏会导致空白界面"
                            )
                        }
                        return@forEach
                    }
                }
                effectiveNow.add(rule.id)
                // 先把控件长什么样拍下来（拍完才隐藏，否则就拍不到了）
                capturePurifyShot(activity, view, rule.id)
                if (applyPurifyAction(view, rule)) {
                    hits[rule.id] = (hits[rule.id] ?: 0) + 1
                }
            } catch (_: Throwable) {
            }
        }

        if (hits.isNotEmpty()) {
            recordPurifyHits(activity, hits)
            // 写进模块日志（带「净化」标记），否则净化页的日志永远是空的
            val hostPkg = activity.packageName
            hits.forEach { (id, count) ->
                if (purifyHitsLogged.add("$hostPkg:$id")) {
                    val rule = rules.firstOrNull { it.id == id }
                    writeLogFor(
                        activity, hostPkg, FEATURE_PURIFY,
                        "已净化 ${count} 处：${rule?.summary() ?: id}"
                    )
                }
            }
        }
        // 被黑屏保护拦下的也写一条，方便在日志里对照
        skippedNow.forEach { (id, reason) ->
            if (purifySkipLogOnce.add("${activity.packageName}:$id")) {
                val rule = rules.firstOrNull { it.id == id }
                writeLogFor(
                    activity, activity.packageName, FEATURE_PURIFY,
                    "已跳过：${rule?.summary() ?: id}（$reason）"
                )
            }
        }

        // 把跳过原因同步给模块 UI（节流 + 后台写盘）
        val recovered = effectiveNow - skippedNow.keys
        recordPurifySkipped(activity, skippedNow, recovered)
    }

    /**
     * 记录净化命中数。同理 [recordPurifySkipped]：这里跑在 OnPreDraw 回调里，
     * 直接读文件再写回会卡住帧，先在内存累加，延迟后交给后台线程写一次。
     */
    private fun recordPurifyHits(context: android.content.Context, hits: Map<String, Int>) {
        if (hits.isEmpty()) return
        try {
            val appContext = context.applicationContext ?: context
            synchronized(purifyHitsLock) {
                val current = purifyHitsCache
                    ?: PurifyRules.loadLocalHits(appContext).toMutableMap()
                hits.forEach { (id, count) -> current[id] = (current[id] ?: 0) + count }
                purifyHitsCache = current
                if (purifyHitsFlushScheduled) return
                purifyHitsFlushScheduled = true
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val write = synchronized(purifyHitsLock) {
                        purifyHitsFlushScheduled = false
                        HashMap(purifyHitsCache ?: emptyMap())
                    }
                    Thread {
                        try {
                            PurifyRules.saveLocalHits(appContext, write)
                        } catch (_: Throwable) {
                        }
                    }.start()
                } catch (_: Throwable) {
                }
            }, PURIFY_SKIPPED_FLUSH_MS)
        } catch (_: Throwable) {
        }
    }

    /**
     * 记录黑屏保护的跳过原因，供模块的规则列表显示。
     *
     * 这个函数由 `applyPurifyRound` 调用，而它跑在主线程（OnPreDraw 回调里），
     * 所以不能直接写文件：先在内存里累积，延迟合并后交给后台线程写一次。
     */
    private fun recordPurifySkipped(
        context: android.content.Context,
        skipped: Map<String, String>,
        recovered: Set<String>
    ) {
        if (skipped.isEmpty() && recovered.isEmpty()) return
        try {
            val appContext = context.applicationContext ?: context
            synchronized(purifySkippedLock) {
                val current = purifySkippedCache
                    ?: PurifyRules.loadLocalSkipped(appContext).toMutableMap()
                skipped.forEach { (id, reason) -> current[id] = reason }
                recovered.forEach { current.remove(it) }
                purifySkippedCache = current
                if (purifySkippedFlushScheduled) return
                purifySkippedFlushScheduled = true
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val write = synchronized(purifySkippedLock) {
                        purifySkippedFlushScheduled = false
                        HashMap(purifySkippedCache ?: emptyMap())
                    }
                    Thread {
                        try {
                            PurifyRules.saveLocalSkipped(appContext, write)
                        } catch (_: Throwable) {
                        }
                    }.start()
                } catch (_: Throwable) {
                }
            }, PURIFY_SKIPPED_FLUSH_MS)
        } catch (_: Throwable) {
        }
    }

    // ---- 翻译（纯离线词库） ----

    /**
     * 给这个界面装翻译：把文字控件里的英文换成中文。
     *
     * 翻不到的词保持英文原样，不硬翻。同一个界面只装一次。
     */
    private fun installTranslateHooks(activity: Activity, pkg: String) {
        // 诊断：开关没开时在日志里写明，避免"看不到效果又不知道卡在哪一步"
        if (!Features.isOn(activity, Features.MASTER) || !Features.isOn(activity, Features.TRANSLATE)) {
            if (translateVerboseLogged.add("off:$pkg")) {
                writeLogFor(
                    activity, pkg, FEATURE_TRANSLATE,
                    "翻译诊断：本应用的翻译开关没打开（主页总开关，或功能里的翻译）"
                )
            }
            return
        }
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return
        synchronized(translateInstalledLock) {
            if (!translateInstalled.add(activity)) return
        }
        val dict = translateDict(activity)
        if (dict.isEmpty()) {
            if (translateVerboseLogged.add("empty:$pkg")) {
                writeLogFor(activity, pkg, FEATURE_TRANSLATE, "翻译诊断：词库读出来是空的，翻不了")
            }
            return
        }

        // OnPreDraw：在画面出来之前就换掉，避免先看到英文再跳成中文
        try {
            val observer = decor.viewTreeObserver
            var draws = 0
            val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    draws++
                    try {
                        if (isAlive(activity)) applyTranslateRound(activity, dict)
                    } catch (_: Throwable) {
                    }
                    if (draws >= TRANSLATE_PRE_DRAW_ROUNDS) {
                        try {
                            if (observer.isAlive) observer.removeOnPreDrawListener(this)
                        } catch (_: Throwable) {
                        }
                    }
                    return true
                }
            }
            observer.addOnPreDrawListener(listener)
        } catch (_: Throwable) {
        }

        // 兜底：列表、网络数据这类文字是后加载的，再补几轮
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var round = 0
        val task = object : Runnable {
            override fun run() {
                if (!isAlive(activity)) return
                round++
                try {
                    applyTranslateRound(activity, dict)
                } catch (_: Throwable) {
                }
                if (round < TRANSLATE_SCAN_ROUNDS) handler.postDelayed(this, TRANSLATE_SCAN_DELAY)
            }
        }
        handler.postDelayed(task, TRANSLATE_SCAN_DELAY)
        translateLogged.add(pkg)
    }

    /** 词库缓存：3 秒内复用，避免每次界面变化都读盘。 */
    private fun translateDict(context: android.content.Context): Map<String, String> {
        val now = System.currentTimeMillis()
        val cached = translateDictCache
        if (cached != null && now - translateDictCacheAt < TRANSLATE_DICT_TTL) return cached
        val fresh = TranslateDict.loadLocal(context)
        translateDictCache = fresh
        translateDictCacheAt = now
        return fresh
    }

    /** 翻译选项缓存：带危险符号的字符串是否跳过（3 秒）。 */
    private fun translateSkipSymbols(context: android.content.Context): Boolean {
        val now = System.currentTimeMillis()
        val cached = translateSkipSymbolsCache
        if (cached != null && now - translateSkipSymbolsCacheAt < TRANSLATE_DICT_TTL) return cached
        val fresh = TranslateDict.loadLocalSkipSymbols(context)
        translateSkipSymbolsCache = fresh
        translateSkipSymbolsCacheAt = now
        return fresh
    }

    /** 走一遍界面，把能翻的文字换掉。 */
    private fun applyTranslateRound(activity: Activity, dict: Map<String, String>) {
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return
        val skipSymbols = translateSkipSymbols(activity)
        var changed = 0
        var scanned = 0
        walkViews(decor, 0) { view ->
            if (isOwnView(view)) return@walkViews
            // 输入框不碰：那是用户要打字的地方，改了会把内容弄乱
            if (view is android.widget.EditText) return@walkViews
            val textView = view as? android.widget.TextView ?: return@walkViews
            scanned++
            try {
                val original = textView.text?.toString() ?: return@walkViews
                if (original.isBlank()) return@walkViews
                val translated = TranslateDict.translate(original, dict, skipSymbols) ?: return@walkViews
                if (translated == original) return@walkViews
                textView.text = translated
                changed++
            } catch (_: Throwable) {
            }
        }

        // 第一轮把结果写进日志：能直接看出"是翻不出来"还是"界面根本没有文字控件"
        if (translateVerboseLogged.add(activity.javaClass.simpleName)) {
            val pkgName = activity.packageName
            writeLogFor(
                activity, pkgName, FEATURE_TRANSLATE,
                "翻译诊断：${activity.javaClass.simpleName} 里有 $scanned 个文字控件，翻了 $changed 处"
            )
            if (scanned == 0) {
                writeLogFor(
                    activity, pkgName, FEATURE_TRANSLATE,
                    "翻译诊断：这个界面一个文字控件都没有，说明它不是用普通控件画的" +
                        "（Compose / Flutter / 游戏界面都是这样），这类界面翻不了"
                )
            } else if (changed == 0) {
                writeLogFor(
                    activity, pkgName, FEATURE_TRANSLATE,
                    "翻译诊断：找到 $scanned 个文字控件，但词库一个都没命中，去「自己加词条」补"
                )
            }
        }
        if (changed > 0) {
            logFramework("自用模块: 翻译了 $changed 处文字（${activity.javaClass.simpleName}）")
        }
    }

    /**
     * 把控件当前的样子拍成一张小图，存到目标应用自己的目录里。
     *
     * 注意时机：必须在隐藏之前拍，隐藏之后控件就画不出来了。
     * 每条规则只留一张（文件名就是规则编号），已经有图就不再重复拍，
     * 避免每次界面变化都拍一遍、白白占用 CPU。
     */
    private fun capturePurifyShot(
        context: android.content.Context,
        view: android.view.View,
        ruleId: String
    ) {
        try {
            if (ruleId.isBlank()) return
            if (ruleId in purifyShotTaken) return
            val dir = java.io.File(context.filesDir, PurifyRules.SHOT_DIR).apply { mkdirs() }
            val file = java.io.File(dir, "$ruleId.png")
            if (file.exists() && file.length() > 0) {
                purifyShotTaken.add(ruleId)
                return
            }

            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return
            if (!purifyShotTaken.add(ruleId)) return

            // 小图：宽度压到上限以内，省地方也够认出是什么
            val scale = if (width > PURIFY_SHOT_MAX_WIDTH) {
                PURIFY_SHOT_MAX_WIDTH.toFloat() / width
            } else {
                1f
            }
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)

            val bitmap = android.graphics.Bitmap.createBitmap(
                targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(scale, scale)
            view.draw(canvas)
            // 位图必须在主线程画，但 PNG 编码和写文件不用：丢到后台线程，不占帧预算
            Thread {
                try {
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, out)
                    }
                    logFramework("自用模块: 已保存控件小图 $ruleId（${targetWidth}x$targetHeight）")
                } catch (_: Throwable) {
                } finally {
                    bitmap.recycle()
                }
            }.start()
        } catch (_: Throwable) {
        }
    }

    /** 递归遍历；每个子 View 单独包 try/catch，个别控件异常不影响整棵树。 */
    private fun walkViews(root: android.view.View, depth: Int, visitor: (android.view.View) -> Unit) {
        if (depth > PURIFY_MAX_DEPTH) return
        try {
            visitor(root)
        } catch (_: Throwable) {
        }
        if (root !is android.view.ViewGroup) return
        val count = try {
            root.childCount
        } catch (_: Throwable) {
            return
        }
        for (index in 0 until count) {
            val child = try {
                root.getChildAt(index)
            } catch (_: Throwable) {
                null
            } ?: continue
            walkViews(child, depth + 1, visitor)
        }
    }

    /** 模块自己挂上去的视图（悬浮球 / 点选层）不参与净化匹配。 */
    private fun isOwnView(view: android.view.View): Boolean {
        val tag = view.tag
        return tag is String && tag.startsWith("selfxposed_")
    }

    private fun idNameOf(view: android.view.View): String = try {
        val id = view.id
        if (id == android.view.View.NO_ID) "" else view.resources.getResourceEntryName(id)
    } catch (_: Throwable) {
        ""
    }

    private fun applyPurifyAction(view: android.view.View, rule: PurifyRules.Rule): Boolean = try {
        when (rule.action) {
            PurifyRules.ACTION_HIDE -> {
                if (view.visibility == android.view.View.GONE) {
                    false
                } else {
                    view.visibility = android.view.View.GONE
                    true
                }
            }

            PurifyRules.ACTION_INVISIBLE -> {
                if (view.visibility == android.view.View.INVISIBLE) {
                    false
                } else {
                    view.visibility = android.view.View.INVISIBLE
                    true
                }
            }

            PurifyRules.ACTION_REMOVE -> {
                // 不要真的 removeView：把控件从树里摘掉会让 ViewPager / RecyclerView /
                // Fragment 的父子关系失配，目标应用可能整屏黑掉（实测反馈）。
                // 用「GONE + 尺寸归零」达到视觉上完全移除的效果，树结构保持完整。
                var changed = false
                if (view.visibility != android.view.View.GONE) {
                    view.visibility = android.view.View.GONE
                    changed = true
                }
                val params = view.layoutParams
                if (params != null && (params.width != 0 || params.height != 0)) {
                    params.width = 0
                    params.height = 0
                    view.layoutParams = params
                    changed = true
                }
                changed
            }

            PurifyRules.ACTION_TEXT -> {
                val textView = view as? android.widget.TextView
                if (textView == null || textView.text?.toString() == rule.arg) false else {
                    textView.text = rule.arg
                    true
                }
            }

            PurifyRules.ACTION_SIZE -> {
                val size = parsePurifySize(view, rule.arg)
                val params = view.layoutParams
                if (size == null || params == null) false else {
                    if (params.width == size.first && params.height == size.second) {
                        false
                    } else {
                        params.width = size.first
                        params.height = size.second
                        view.layoutParams = params
                        true
                    }
                }
            }

            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    /** "200x60"（dp）→ 像素；-1 / -2 原样保留。 */
    private fun parsePurifySize(view: android.view.View, arg: String): Pair<Int, Int>? {
        val parts = arg.lowercase(Locale.ROOT).split('x')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        val density = view.resources.displayMetrics.density
        fun convert(value: Int) = when (value) {
            -1, -2 -> value
            else -> (value * density + 0.5f).toInt()
        }
        return convert(w) to convert(h)
    }

    /** 点悬浮球：直接进入点选（净化优先；只有拦截弹窗开启时进入弹窗拾取）。 */
    private fun onBallTapped(context: android.content.Context, pkg: String) {
        val activity = findActivity(context)
        if (activity == null || !isAlive(activity)) {
            logFramework("自用模块: 悬浮球点选需要一个存活的 Activity")
            return
        }
        if (Features.isOn(activity, Features.PURIFY)) {
            startPurifyPicking(activity, pkg)
        } else {
            startPicking(context, pkg)
        }
    }

    /** 从可能被包装过的 Context 里找出真正的 Activity。 */
    private fun findActivity(context: android.content.Context?): Activity? {
        var current = context
        var guard = 0
        while (current is android.content.ContextWrapper && guard++ < 8) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /**
     * 进入点选净化。
     *
     * 手指滑过时把「触摸点所在的整条祖先链」记下来，默认停在最深一层；
     * 底部工具条的 ▲ 父级 / ▼ 子级 用来在链上进退，解决"只框到整个屏幕"的问题。
     * 只有点 ✓ 才弹动作卡，避免抬手就误触。
     */
    private fun startPurifyPicking(activity: Activity?, pkg: String) {
        if (activity == null) {
            logFramework("自用模块: 点选净化需要一个 Activity 上下文")
            return
        }
        if (!isAlive(activity)) return
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return
        if (decor.findViewWithTag<android.view.View>(PURIFY_PICK_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val overlay = android.widget.FrameLayout(activity).apply {
            tag = PURIFY_PICK_TAG
            setBackgroundColor(android.graphics.Color.argb(24, 0, 0, 0))
            isClickable = true
        }

        val frame = android.view.View(activity).apply {
            tag = PURIFY_PICK_TAG + "_frame"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(2), android.graphics.Color.parseColor("#6750A4"))
                cornerRadius = dp(4).toFloat()
            }
        }
        overlay.addView(frame, android.widget.FrameLayout.LayoutParams(0, 0))

        // 计数放在底部工具条上方的一行，不再跟着方框走：
        // 之前标签贴在方框正上方，把要看的区域挡住了。
        val counter = android.widget.TextView(activity).apply {
            tag = PURIFY_PICK_TAG + "_counter"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            setBackgroundColor(android.graphics.Color.argb(220, 32, 32, 32))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            text = "点一下要净化的位置"
        }
        overlay.addView(counter, android.widget.FrameLayout.LayoutParams(-2, -2).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = dp(76)
            leftMargin = dp(12)
        })

        // ---- 底部工具条（可拖动）----
        val barRow = android.widget.LinearLayout(activity).apply {
            tag = PURIFY_PICK_TAG + "_bar"
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.argb(235, 32, 32, 32))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val buttonStrip = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        // 工具条的位置参数要在按钮/提示之前声明：拖动和长按提示都要用到
        val barParams = android.widget.FrameLayout.LayoutParams(-2, -2).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        // ---- 图标提示：长按图标才显示文字，1.5 秒后淡出 ----
        val iconHint = android.widget.TextView(activity).apply {
            tag = PURIFY_PICK_TAG + "_hint"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(235, 32, 32, 32))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = android.view.View.INVISIBLE
            alpha = 0f
            isSingleLine = true
        }
        overlay.addView(iconHint, android.widget.FrameLayout.LayoutParams(-2, -2))

        val hintHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var hintFadeOut: Runnable? = null
        fun showIconHint(label: String) {
            try {
                if (label.isEmpty()) return
                iconHint.text = label
                // 量一次宽度，才能把提示水平居中对到工具条上方
                iconHint.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                )
                val lp = iconHint.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
                val centerX = barParams.leftMargin + barRow.width / 2
                lp.leftMargin = (centerX - iconHint.measuredWidth / 2).coerceAtLeast(0)
                lp.topMargin = (barParams.topMargin - iconHint.measuredHeight - dp(6)).coerceAtLeast(0)
                iconHint.layoutParams = lp
                iconHint.visibility = android.view.View.VISIBLE
                iconHint.animate().cancel()
                iconHint.animate().alpha(1f).setDuration(120).start()
                hintFadeOut?.let { hintHandler.removeCallbacks(it) }
                val fade = Runnable {
                    iconHint.animate().cancel()
                    iconHint.animate().alpha(0f).setDuration(300)
                        .withEndAction { iconHint.visibility = android.view.View.INVISIBLE }
                        .start()
                }
                hintFadeOut = fade
                hintHandler.postDelayed(fade, PURIFY_ICON_HINT_MS)
            } catch (_: Throwable) {
            }
        }

        // 按钮只显示图标，文字长按才出现。
        // 尺寸必须固定、且父容器不能用 weight：之前按钮放在 wrap_content 的
        // 父容器里用 weight 分宽度，测量结果不稳定，拖动工具条时图标下的文字
        // 反复换行导致高度跳变，看起来就是上下抖。
        fun barButton(icon: String, label: String): android.widget.TextView =
            android.widget.TextView(activity).apply {
                this.text = icon
                tag = label
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                isClickable = true
                setOnLongClickListener {
                    showIconHint(tag as? String ?: "")
                    true
                }
            }

        // 最右侧的拖动图标：按住它移动整条工具条
        val dragHandle = android.widget.TextView(activity).apply {
            tag = PURIFY_PICK_TAG + "_handle"
            text = "⠿"
            setTextColor(android.graphics.Color.parseColor("#8CE99A"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            isSingleLine = true
            isClickable = true
        }
        barRow.addView(buttonStrip, android.widget.LinearLayout.LayoutParams(-2, -2))
        barRow.addView(dragHandle, android.widget.LinearLayout.LayoutParams(-2, -2))

        var chain: List<android.view.View> = emptyList()
        var index = 0

        fun current(): android.view.View? = chain.getOrNull(index)

        fun refresh() {
            val target = current() ?: run {
                frame.layoutParams = android.widget.FrameLayout.LayoutParams(0, 0)
                counter.text = "点一下要净化的位置"
                return
            }
            drawPurifyTarget(frame, target, decor)
            try {
                val decorArea = (decor.width.toLong() * decor.height.toLong()).coerceAtLeast(1L)
                val percent = (target.width.toLong() * target.height.toLong() * 100L / decorArea).toInt()
                val suffix = " · 占屏 $percent%"
                counter.text = if (chain.size <= 1) {
                    "只有 1 个候选$suffix · 长按图标看名称"
                } else {
                    "候选 ${index + 1}/${chain.size}$suffix · 长按图标看名称"
                }
            } catch (_: Throwable) {
            }
        }

        // 候选列表按面积从小到大排：index 越小越精确，越大越接近整屏
        val smallerButton = barButton("◀", "更小") .apply {
            setOnClickListener {
                if (index > 0) {
                    index--
                    refresh()
                }
            }
        }
        val biggerButton = barButton("▶", "更大").apply {
            setOnClickListener {
                if (index < chain.size - 1) {
                    index++
                    refresh()
                }
            }
        }
        // 「隐藏 / 显示」：按保存规则会命中的范围预览，再点一次恢复
        val previewHidden = ArrayList<Pair<android.view.View, Int>>()
        fun clearPreview() {
            previewHidden.forEach { (view, visibility) ->
                try {
                    view.visibility = visibility
                } catch (_: Throwable) {
                }
            }
            previewHidden.clear()
        }
        val hideButton = barButton("◉", "隐藏").apply {
            setOnClickListener {
                val target = current() ?: return@setOnClickListener
                if (previewHidden.isNotEmpty()) {
                    clearPreview()
                    text = "◉"
                    tag = "隐藏"
                    refresh()
                    return@setOnClickListener
                }
                val selector = purifyRuleSelector(target)
                if (selector == null) {
                    counter.text = "这个控件不能自动净化，预览无法显示保存效果"
                    return@setOnClickListener
                }
                val rule = PurifyRules.Rule(
                    id = "preview",
                    on = true,
                    type = selector.first,
                    value = selector.second,
                    action = PurifyRules.ACTION_HIDE
                )
                val activityName = activity.javaClass.simpleName
                val decorArea = (decor.width.toLong() * decor.height.toLong()).coerceAtLeast(1L)
                var count = 0
                walkViews(decor, 0) { view ->
                    if (isOwnView(view)) return@walkViews
                    if (view.parent == null) return@walkViews
                    val idName = idNameOf(view)
                    val viewText = (view as? android.widget.TextView)?.text?.toString()?.trim().orEmpty()
                    val desc = view.contentDescription?.toString()?.trim().orEmpty()
                    val className = view.javaClass.name
                    if (!PurifyRules.matches(rule, activityName, idName, viewText, desc, className)) {
                        return@walkViews
                    }
                    // 保存时也会跳过占屏过大的控件，预览保持一致
                    val area = view.width.toLong() * view.height.toLong()
                    if (area * 100L / decorArea >= PURIFY_LARGE_PERCENT) return@walkViews
                    previewHidden.add(view to view.visibility)
                    view.visibility = android.view.View.GONE
                    count++
                }
                text = "○"
                tag = "显示"
                counter.text = counter.text.toString() + " · 已隐藏 $count 处"
            }
        }

        // 「详情」：进入独立的详情界面看这个控件的信息
        val detailButton = barButton("ⓘ", "详情").apply {
            setOnClickListener {
                val target = current() ?: return@setOnClickListener
                showPurifyDetails(activity, target, index, chain.size, decor)
            }
        }

        val okButton = barButton("✓", "用它").apply {
            setTextColor(android.graphics.Color.parseColor("#8CE99A"))
            setOnClickListener {
                val target = current() ?: return@setOnClickListener
                // 收尾：把预览隐藏过的控件恢复，避免留下"半隐藏"的状态
                clearPreview()
                hideButton.text = "◉"
                hideButton.tag = "隐藏"
                // 能正常建规则时，点「✓」就收起选区；不能建规则时保留选区，
                // 让「这个控件不能自动净化」提示关闭后还能用「◀ 更小」重选。
                if (canBuildPurifyRule(target)) {
                    (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
                }
                showPurifyActionCard(activity, pkg, target)
            }
        }
        val cancelButton = barButton("✗", "取消").apply {
            setTextColor(android.graphics.Color.parseColor("#FFA8A8"))
            setOnClickListener {
                clearPreview()
                hideButton.text = "◉"
                hideButton.tag = "隐藏"
                (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
            }
        }
        // 每个按钮固定尺寸，不用 weight 分配宽度（weight 会导致反复测量、图标抖动）
        val buttonSize = dp(36)
        listOf(smallerButton, biggerButton, hideButton, detailButton, okButton, cancelButton)
            .forEach { button ->
                button.setPadding(0, 0, 0, 0)
                buttonStrip.addView(
                    button,
                    android.widget.LinearLayout.LayoutParams(buttonSize, buttonSize)
                )
            }

        // 工具条可拖动：wrap_content + 绝对边距，最右侧手柄拖动就改边距
        overlay.addView(barRow, barParams)

        var barDownX = 0f
        var barDownY = 0f
        var barStartLeft = 0
        var barStartTop = 0
        var barDragging = false
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    barDownX = event.rawX
                    barDownY = event.rawY
                    barStartLeft = barParams.leftMargin
                    barStartTop = barParams.topMargin
                    barDragging = false
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - barDownX).toInt()
                    val dy = (event.rawY - barDownY).toInt()
                    if (!barDragging && (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4))) {
                        barDragging = true
                    }
                    if (barDragging) {
                        // 限制在屏幕内，别拖出可视区域
                        val maxLeft = (overlay.width - barRow.width).coerceAtLeast(0)
                        val maxTop = (overlay.height - barRow.height).coerceAtLeast(0)
                        barParams.leftMargin = (barStartLeft + dx).coerceIn(0, maxLeft)
                        barParams.topMargin = (barStartTop + dy).coerceIn(0, maxTop)
                        barRow.layoutParams = barParams
                    }
                    true
                }

                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        var downX = 0f
        var downY = 0f
        var moved = false

        // 切换到别的控件时，先把上一次「隐藏」预览恢复，避免留下看不见的控件
        fun restorePreview() {
            clearPreview()
            hideButton.text = "◉"
            hideButton.tag = "隐藏"
        }

        overlay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    restorePreview()
                    chain = collectHitCandidates(decor, event.rawX, event.rawY)
                    // 默认停在面积最小的那个（最精确），需要整块区域再用「更大」往外走
                    index = 0
                    refresh()
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    // 超过阈值才重算，避免轻微抖动把用户选好的控件重置掉
                    if (!moved &&
                        (Math.abs(event.rawX - downX) > dp(8) || Math.abs(event.rawY - downY) > dp(8))
                    ) {
                        moved = true
                        restorePreview()
                        chain = collectHitCandidates(decor, event.rawX, event.rawY)
                        index = 0
                        refresh()
                    }
                    true
                }

                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }

        decor.addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1))

        // 初始位置：屏幕底部居中。之后用户拖右侧「⠿」手柄可以任意移动。
        overlay.post {
            try {
                val maxLeft = (overlay.width - barRow.width).coerceAtLeast(0)
                val maxTop = (overlay.height - barRow.height).coerceAtLeast(0)
                barParams.leftMargin = (maxLeft / 2).coerceAtLeast(0)
                barParams.topMargin = (maxTop - dp(24)).coerceAtLeast(0)
                barRow.layoutParams = barParams
            } catch (_: Throwable) {
            }
        }

        refresh()
        try {
            android.widget.Toast.makeText(activity, "点选控件，◀▶ 调范围", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
        logFramework("自用模块: 进入点选净化（$pkg）")
    }

    /** Activity 还活着才允许弹窗 / 改界面，否则会 BadTokenException 崩掉目标应用。 */
    private fun isAlive(activity: Activity?): Boolean = try {
        activity != null && !activity.isFinishing && !activity.isDestroyed
    } catch (_: Throwable) {
        false
    }

    /**
     * 收集所有「包含触摸点」的控件，按面积从小到大排序（index 0 = 最小 = 最精确）。
     *
     * 之前沿祖先链上下走，遇到"全屏容器 + 全屏触摸拦截器"的应用（广告 SDK 常见做法）时，
     * 点到的控件和它的所有祖先都是全屏，怎么调都是整个屏幕。
     * 改成遍历整棵树收集候选，就能选到同一位置上的小兄弟控件。
     */
    private fun collectHitCandidates(
        root: android.view.ViewGroup,
        rawX: Float,
        rawY: Float
    ): List<android.view.View> {
        val found = ArrayList<android.view.View>(16)
        try {
            collectHitCandidatesInto(root, rawX, rawY, 0, found)
        } catch (_: Throwable) {
        }
        // 面积小的排前面；面积相同则层级更深的优先
        return found.sortedWith(
            compareBy<android.view.View> { it.width.toLong() * it.height.toLong() }
                .thenByDescending { depthOf(it) }
        ).take(PURIFY_MAX_CANDIDATES)
    }

    private fun collectHitCandidatesInto(
        node: android.view.View,
        rawX: Float,
        rawY: Float,
        depth: Int,
        out: MutableList<android.view.View>
    ) {
        if (depth > PURIFY_MAX_DEPTH) return
        // 模块自己的视图（悬浮球、点选层）整棵跳过：只跳过节点本身不够，
        // 点选层的标签/按钮没有 tag，会被当成候选控件选中。
        if (isOwnView(node)) return
        if (node !== node.rootView && containsPoint(node, rawX, rawY)) {
            out.add(node)
        }
        if (node !is android.view.ViewGroup) return
        val count = try {
            node.childCount
        } catch (_: Throwable) {
            return
        }
        for (index in 0 until count) {
            val child = try {
                node.getChildAt(index)
            } catch (_: Throwable) {
                null
            } ?: continue
            collectHitCandidatesInto(child, rawX, rawY, depth + 1, out)
        }
    }

    private fun containsPoint(view: android.view.View, rawX: Float, rawY: Float): Boolean = try {
        if (view.visibility != android.view.View.VISIBLE) {
            false
        } else if (view.width <= 0 || view.height <= 0) {
            false
        } else {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            rawX >= loc[0] && rawX <= loc[0] + view.width &&
                rawY >= loc[1] && rawY <= loc[1] + view.height
        }
    } catch (_: Throwable) {
        false
    }

    /** 控件在树里的深度，用于同面积时优先更深的那个。 */
    private fun depthOf(view: android.view.View): Int {
        var depth = 0
        var current: android.view.View? = try {
            view.parent as? android.view.View
        } catch (_: Throwable) {
            null
        }
        while (current != null && depth < PURIFY_MAX_DEPTH) {
            depth++
            current = try {
                current.parent as? android.view.View
            } catch (_: Throwable) {
                null
            }
        }
        return depth
    }

    /** 只画方框，不再往方框旁边贴文字（贴字会挡住要看的区域）。 */
    private fun drawPurifyTarget(
        frame: android.view.View,
        target: android.view.View,
        decor: android.view.ViewGroup
    ) {
        try {
            val decorLoc = IntArray(2)
            decor.getLocationOnScreen(decorLoc)
            val targetLoc = IntArray(2)
            target.getLocationOnScreen(targetLoc)

            val params = frame.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
            params.width = target.width
            params.height = target.height
            params.leftMargin = targetLoc[0] - decorLoc[0]
            params.topMargin = targetLoc[1] - decorLoc[1]
            frame.layoutParams = params
        } catch (_: Throwable) {
        }
    }

    private fun describePurifyView(view: android.view.View): String = try {
        buildString {
            append(view.javaClass.simpleName)
            val idName = idNameOf(view)
            if (idName.isNotEmpty()) append(" #").append(idName)
            val text = (view as? android.widget.TextView)?.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) append(" 「").append(text.take(16)).append("」")
            // 带上尺寸：宽高接近屏幕的就是"整个界面"，方便判断要不要按 ▲ 往上退
            val density = view.resources.displayMetrics.density
            if (density > 0f && view.width > 0 && view.height > 0) {
                val w = (view.width / density + 0.5f).toInt()
                val h = (view.height / density + 0.5f).toInt()
                append("  ").append(w).append("×").append(h).append("dp")
            }
        }
    } catch (_: Throwable) {
        ""
    }

    /**
     * 整块容器（占屏 >= PURIFY_LARGE_PERCENT）隐藏/移除前先警告。
     * 规则一旦写进去，目标应用下次启动就是空白界面，只能回模块删规则，代价太高。
     */
    private fun confirmIfLargeTarget(
        activity: Activity,
        target: android.view.View,
        actionLabel: String,
        onConfirm: () -> Unit
    ) {
        if (!isAlive(activity)) return
        try {
            val root = target.rootView
            val rootArea = (root.width.toLong() * root.height.toLong()).coerceAtLeast(1L)
            val area = target.width.toLong() * target.height.toLong()
            val percent = (area * 100L / rootArea).toInt()
            if (percent < PURIFY_LARGE_PERCENT) {
                onConfirm()
                return
            }
            android.app.AlertDialog.Builder(activity)
                .setTitle("这是整块区域")
                .setMessage(
                    "选中控件占了屏幕的 $percent%，是一整块容器。\n\n" +
                        "把它$actionLabel 掉，界面很可能整片空白。\n" +
                        "建议先点「取消」，再用「◀ 更小」选更精确的控件。\n\n" +
                        "确定要$actionLabel 它吗？"
                )
                .setPositiveButton("确定$actionLabel") { _, _ -> onConfirm() }
                .setNegativeButton("取消", null)
                .show()
        } catch (_: Throwable) {
            onConfirm()
        }
    }

    /**
     * 自绘对话框：固定使用模块配色。
     *
     * 系统 AlertDialog 会跟随目标应用的主题，同一个功能在不同软件里背景/文字颜色都不一样，
     * 所以统一自己画：半透明遮罩 + 圆角卡片 + Palette 配色。
     *
     * @param menu 竖排菜单项（点哪项就关闭对话框再执行）
     * @param buttons 底部横排按钮，最后一项用主色
     */
    private fun showModuleDialog(
        activity: Activity,
        title: String,
        message: String? = null,
        body: android.view.View? = null,
        cancelable: Boolean = true,
        menu: List<Pair<String, () -> Unit>> = emptyList(),
        buttons: List<Pair<String, () -> Unit>> = emptyList()
    ): android.app.Dialog? {
        if (!isAlive(activity)) return null
        return try {
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val ink = com.example.selfxposed.ui.Palette.ink
            val muted = com.example.selfxposed.ui.Palette.muted
            val accent = com.example.selfxposed.ui.Palette.accent
            val surfaceMuted = com.example.selfxposed.ui.Palette.surfaceMuted

            val dialog = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
            val wrapper = android.widget.FrameLayout(activity).apply {
                setBackgroundColor(android.graphics.Color.argb(140, 0, 0, 0))
                setPadding(dp(24), dp(24), dp(24), dp(24))
                isClickable = true
                setOnClickListener { if (cancelable) dialog.dismiss() }
            }
            val card = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(com.example.selfxposed.ui.Palette.surface)
                    cornerRadius = dp(18).toFloat()
                }
                elevation = dp(8).toFloat()
                isClickable = true
            }
            card.addView(android.widget.TextView(activity).apply {
                this.text = title
                setTextColor(ink)
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                includeFontPadding = false
            })

            if (!message.isNullOrEmpty()) {
                val bodyText = android.widget.TextView(activity).apply {
                    this.text = message
                    setTextColor(muted)
                    textSize = 12f
                    setPadding(0, dp(10), 0, dp(2))
                    setLineSpacing(dp(3).toFloat(), 1f)
                }
                val scroll = android.widget.ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = true
                    addView(bodyText, android.widget.FrameLayout.LayoutParams(-1, -2))
                }
                card.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, -2))
            }

            if (body != null) {
                card.addView(body, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(10)
                })
            }

            menu.forEach { entry ->
                val row = android.widget.TextView(activity).apply {
                    this.text = entry.first
                    setTextColor(ink)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(surfaceMuted)
                        cornerRadius = dp(12).toFloat()
                    }
                    isClickable = true
                    setOnClickListener {
                        dialog.dismiss()
                        try {
                            entry.second()
                        } catch (_: Throwable) {
                        }
                    }
                }
                card.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                })
            }

            if (buttons.isNotEmpty()) {
                val row = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                }
                buttons.forEachIndexed { position, entry ->
                    // 只有一个按钮时用次要色（通常是「取消」），多个时最后一个是主操作
                    val isPrimary = buttons.size > 1 && position == buttons.lastIndex
                    val button = android.widget.TextView(activity).apply {
                        this.text = entry.first
                        setTextColor(if (isPrimary) android.graphics.Color.WHITE else muted)
                        textSize = 14f
                        gravity = android.view.Gravity.CENTER
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (isPrimary) accent else surfaceMuted)
                            cornerRadius = dp(20).toFloat()
                        }
                        isClickable = true
                        setOnClickListener {
                            dialog.dismiss()
                            try {
                                entry.second()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    row.addView(button, android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                        leftMargin = if (position == 0) 0 else dp(8)
                    })
                }
                card.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(16)
                })
            }

            wrapper.addView(card, android.widget.FrameLayout.LayoutParams(-1, -2).apply {
                gravity = android.view.Gravity.CENTER
            })
            dialog.setContentView(wrapper)
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            dialog.setCanceledOnTouchOutside(cancelable)
            dialog.setCancelable(cancelable)
            dialog.show()
            dialog
        } catch (t: Throwable) {
            logFramework("自用模块: 对话框显示失败：${describe(t)}")
            null
        }
    }

    /**
     * 点选层底部「详情」按钮弹出的控件详情界面。
     * 以前这些信息贴在方框正上方，挡住了要看的区域，所以独立成对话框。
     * 顺便把「候选数量」显示出来：只有 1 个候选说明界面是自绘的，内部元素点不到。
     */
    private fun showPurifyDetails(
        activity: Activity,
        target: android.view.View,
        index: Int,
        total: Int,
        decor: android.view.ViewGroup
    ) {
        if (!isAlive(activity)) return
        try {
            val density = target.resources.displayMetrics.density
            val toDp = { value: Int -> if (density > 0f) (value / density + 0.5f).toInt() else value }
            val decorArea = (decor.width.toLong() * decor.height.toLong()).coerceAtLeast(1L)
            val area = target.width.toLong() * target.height.toLong()
            val percent = (area * 100L / decorArea).toInt()
            val text = (target as? android.widget.TextView)?.text?.toString()?.trim().orEmpty()
            val desc = target.contentDescription?.toString()?.trim().orEmpty()
            val idName = idNameOf(target)

            val message = buildString {
                append("类名：").append(target.javaClass.name).append('\n')
                append("ID：").append(idName.ifBlank { "（无）" }).append('\n')
                append("文字：").append(text.ifBlank { "（无）" }).append('\n')
                if (desc.isNotEmpty()) append("描述：").append(desc).append('\n')
                append("尺寸：")
                    .append(toDp(target.width)).append("×").append(toDp(target.height)).append(" dp")
                    .append("（").append(target.width).append("×").append(target.height).append(" px）\n")
                append("占屏：").append(percent).append("%\n")
                append("候选：第 ").append(index + 1).append(" / 共 ").append(total).append(" 个")
                if (total <= 1) {
                    append("\n\n⚠ 这个位置只找到 1 个控件。")
                    append("说明该界面是自绘的（WebView / Flutter / Unity），")
                    append("内部元素没有独立控件，只能整块处理。")
                } else if (percent >= PURIFY_LARGE_PERCENT) {
                    append("\n\n⚠ 占屏超过 ").append(PURIFY_LARGE_PERCENT).append("%，是整块容器。")
                    append("隐藏/移除它可能让界面空白，建议点「◀ 更小」选更精确的控件。")
                }
            }

            // 走统一的自绘对话框，固定模块配色
            showModuleDialog(
                activity = activity,
                title = "控件详情",
                message = message,
                buttons = listOf("知道了" to {})
            )
        } catch (t: Throwable) {
            logFramework("自用模块: 控件详情显示失败：${describe(t)}")
        }
    }

    /** 与保存规则完全同一套选择逻辑：优先控件 ID，其次文字，最后非通用类名。 */
    private fun purifyRuleSelector(target: android.view.View): Pair<String, String>? {
        val idName = idNameOf(target)
        if (idName.isNotEmpty()) return PurifyRules.TYPE_ID to idName
        val text = (target as? android.widget.TextView)?.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return PurifyRules.TYPE_TEXT to text
        val className = target.javaClass.name
        if (PurifyRules.isGenericClassName(className)) return null
        return PurifyRules.TYPE_CLASS to className
    }

    private fun canBuildPurifyRule(target: android.view.View): Boolean =
        purifyRuleSelector(target) != null

    /** 抬手后的动作选择：隐藏 / 移除 / 改文字 / 改尺寸。 */
    private fun showPurifyActionCard(activity: Activity, pkg: String, target: android.view.View) {
        if (!isAlive(activity)) return
        try {
            val idName = idNameOf(target)
            val text = (target as? android.widget.TextView)?.text?.toString()?.trim().orEmpty()
            val genericClass = PurifyRules.isGenericClassName(target.javaClass.name)
            // 匹配条件优先资源 id（最稳）；没有 id 才退回文字、最后类名
            val selector = purifyRuleSelector(target)
            if (selector == null) {
                showModuleDialog(
                    activity = activity,
                    title = "这个控件不能自动净化",
                    message = "它没有控件 ID，类名是系统通用控件：\n${target.javaClass.name}\n\n" +
                        "按类名会隐藏界面上所有同类控件（整页空白）。\n" +
                        "请点「◀ 更小」选具体的行或卡片，找带 ID 的子控件再试。",
                    buttons = listOf("知道了" to {})
                )
                return
            }
            val type = selector.first
            val value = selector.second

            // 「隐藏」已移到点选层底部工具条做即时预览，这里只保留需要落规则的三个动作
            showModuleDialog(
                activity = activity,
                title = "净化 · " + describePurifyView(target),
                menu = listOf(
                    "移除（隐藏并压成 0 尺寸）" to {
                        confirmIfLargeTarget(activity, target, "移除") {
                            savePurifyRule(activity, type, value, PurifyRules.ACTION_REMOVE, "", target)
                        }
                    },
                    "移除同类 · ID 包含关键字" to {
                        askPurifyIdKeyword(activity, idName, target)
                    },
                    "移除同类 · 按类名（谨慎）" to {
                        if (genericClass) {
                            showModuleDialog(
                                activity = activity,
                                title = "不能按类名净化",
                                message = "这个控件的类名是系统通用类：\n${target.javaClass.name}\n\n" +
                                    "按类名会把界面上所有同类控件一起隐藏，导致整页空白。\n" +
                                    "请改用「◀ 更小」选具体控件。",
                                buttons = listOf("知道了" to {})
                            )
                        } else {
                            confirmIfLargeTarget(activity, target, "移除") {
                                savePurifyRule(
                                    activity,
                                    PurifyRules.TYPE_CLASS,
                                    target.javaClass.name,
                                    PurifyRules.ACTION_REMOVE,
                                    "",
                                    target
                                )
                            }
                        }
                    },
                    "改文字" to { askPurifyText(activity, type, value, target) },
                    "改尺寸" to { askPurifySize(activity, type, value, target) }
                ),
                buttons = listOf("取消" to {})
            )
        } catch (t: Throwable) {
            logFramework("自用模块: 净化动作卡失败：${describe(t)}")
        }
    }

    /** 同类控件：按控件 ID 包含关键字匹配，预填当前控件的 ID。 */
    private fun askPurifyIdKeyword(
        activity: Activity,
        idName: String,
        target: android.view.View
    ) {
        if (!isAlive(activity)) return
        try {
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val input = android.widget.EditText(activity).apply {
                setText(idName)
                hint = "控件 ID 关键字，例如 ad_container"
                setTextColor(com.example.selfxposed.ui.Palette.ink)
                setHintTextColor(com.example.selfxposed.ui.Palette.muted)
                textSize = 14f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(com.example.selfxposed.ui.Palette.surfaceMuted)
                    cornerRadius = dp(12).toFloat()
                }
                isSingleLine = true
            }
            showModuleDialog(
                activity = activity,
                title = "同类控件 · ID 包含",
                body = input,
                buttons = listOf(
                    "取消" to {},
                    "确定" to {
                        val key = input.text.toString().trim()
                        if (key.isNotEmpty()) {
                            confirmIfLargeTarget(activity, target, "移除") {
                                savePurifyRule(
                                    activity, PurifyRules.TYPE_ID_CONTAINS, key,
                                    PurifyRules.ACTION_REMOVE, "", target
                                )
                            }
                        }
                    }
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun askPurifyText(
        activity: Activity,
        type: String,
        value: String,
        target: android.view.View
    ) {
        if (!isAlive(activity)) return
        try {
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val input = android.widget.EditText(activity).apply {
                setText((target as? android.widget.TextView)?.text?.toString().orEmpty())
                setTextColor(com.example.selfxposed.ui.Palette.ink)
                textSize = 14f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(com.example.selfxposed.ui.Palette.surfaceMuted)
                    cornerRadius = dp(12).toFloat()
                }
                isSingleLine = true
            }
            showModuleDialog(
                activity = activity,
                title = "改成什么文字",
                body = input,
                buttons = listOf(
                    "取消" to {},
                    "确定" to {
                        savePurifyRule(
                            activity, type, value, PurifyRules.ACTION_TEXT, input.text.toString(), target
                        )
                    }
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun askPurifySize(
        activity: Activity,
        type: String,
        value: String,
        target: android.view.View
    ) {
        if (!isAlive(activity)) return
        try {
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            val input = android.widget.EditText(activity).apply {
                hint = "宽x高，单位 dp，例如 0x0 或 200x60"
                setText("0x0")
                setTextColor(com.example.selfxposed.ui.Palette.ink)
                setHintTextColor(com.example.selfxposed.ui.Palette.muted)
                textSize = 14f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(com.example.selfxposed.ui.Palette.surfaceMuted)
                    cornerRadius = dp(12).toFloat()
                }
                isSingleLine = true
            }
            showModuleDialog(
                activity = activity,
                title = "改成什么尺寸",
                body = input,
                buttons = listOf(
                    "取消" to {},
                    "确定" to {
                        savePurifyRule(
                            activity, type, value, PurifyRules.ACTION_SIZE,
                            input.text.toString().trim(), target
                        )
                    }
                )
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 保存一条规则：写目标应用自己的规则文件 + 立刻作用在当前控件上。
     * 规则默认不限界面（广告位通常多个页面都有），需要时可导入后手动填 activity。
     */
    private fun savePurifyRule(
        activity: Activity,
        type: String,
        value: String,
        action: String,
        arg: String,
        target: android.view.View?
    ) {
        if (!isAlive(activity)) return
        try {
            val rule = PurifyRules.Rule(
                id = PurifyRules.newId(),
                on = true,
                hits = 0,
                activity = "",
                type = type,
                value = value,
                action = action,
                arg = arg
            )
            val existing = PurifyRules.loadLocal(activity).toMutableList()
            existing.add(rule)
            // 同样的规则只留最新这条，避免列表里出现两个一模一样的东西
            val merged = PurifyRules.dedupe(existing)
            java.io.File(activity.filesDir, PurifyRules.CONF_FILE)
                .writeText(merged.joinToString("\n") { it.toLine() })
            purifyCache = merged
            purifyCacheAt = System.currentTimeMillis()
            if (target != null) applyPurifyAction(target, rule)
            try {
                android.widget.Toast.makeText(
                    activity,
                    "已净化：${PurifyRules.actionLabel(action)}，重启应用后长期生效",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Throwable) {
            }
            logFramework("自用模块: 已添加净化规则 ${rule.toLine()}")
        } catch (t: Throwable) {
            logFramework("自用模块: 保存净化规则失败：${describe(t)}")
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

        // 悬浮球：总开关 + 「拦截弹窗」或「净化」任一开启就显示，点球直接进入点选
        try {
            if (Features.isOn(activity, Features.MASTER) &&
                (Features.isOn(activity, Features.BLOCK) || Features.isOn(activity, Features.PURIFY))
            ) {
                installPickBall(activity, pkg)
            }
        } catch (t: Throwable) {
            logFramework("自用模块: 悬浮球添加失败：${describe(t)}")
        }

        // 净化：按已保存的规则处理当前界面上的控件（点选生成的那批）
        try {
            applyPurifyRules(activity, pkg)
        } catch (t: Throwable) {
            logFramework("自用模块: 净化失败：${describe(t)}")
        }

        // 翻译：按内置词库把界面上的英文换成中文（纯离线，不联网）
        try {
            installTranslateHooks(activity, pkg)
        } catch (t: Throwable) {
            logFramework("自用模块: 翻译失败：${describe(t)}")
        }

        // 1. 记录一次运行时握手，供模块首页显示“已连接”。
        try {
            ConfigClient.call(resolver, ConfigClient.M_RECORD_HOOK, pkg)
        } catch (t: Throwable) {
            logFramework("自用模块: 记录 Hook 状态失败，$pkg：${describe(t)}")
        }
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

    /** 版本伪装日志：同一个 key 每进程只打一次；同时写一份进模块日志（带「版本伪装」标记）。 */
    private fun logVersionSpoofOnce(key: String, message: String) {
        if (versionLogOnce.add(key)) {
            logFramework("自用模块: $message")
            try {
                val app = currentApp()
                if (app != null) {
                    ConfigClient.logFeature(app.contentResolver, app.packageName, FEATURE_UPDATE, message)
                }
            } catch (_: Throwable) {
            }
        }
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
                            // 只要不是"明确放行"，就先让窗口透明，布局照常进行。
                            // 原来只在"全部拦截"时这么做，按标题/关键字拦的时候用户会先看到弹窗、
                            // 150ms 后才被关掉——这就是"闪一下再消失"。现在先藏起来，
                            // 确定不拦的话再恢复可见（见 restoreForNonBlocked）。
                            if (loadBlockRules(context).mode != "allow") {
                                hideForBlocking(dialog)
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

    /** 记录哪些弹窗被我们提前设成透明了：决定不拦时要恢复可见。 */
    private val hiddenForBlocking = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<android.app.Dialog, Boolean>()
    )

    /** 弹窗显示前先设透明，被拦时用户完全看不到，不会"闪一下再消失"。 */
    private fun hideForBlocking(dialog: android.app.Dialog) {
        try {
            val window = dialog.window ?: return
            val attributes = window.attributes
            attributes.alpha = 0f
            window.attributes = attributes
            hiddenForBlocking[dialog] = true
        } catch (_: Throwable) {
        }
    }

    /** 决定不拦（或要弹询问卡）时，把提前藏起来的弹窗恢复可见。 */
    private fun restoreForNonBlocked(dialog: android.app.Dialog) {
        try {
            if (hiddenForBlocking.remove(dialog) != true) return
            val window = dialog.window ?: return
            val attributes = window.attributes
            attributes.alpha = 1f
            window.attributes = attributes
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
            logBlock(context, pkg, "关键字命中，已拦截弹窗「${title ?: "无标题"}」")
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
            logBlock(context, pkg, "已按规则拦截弹窗「${title ?: "无标题"}」（规则=${rules.mode}）")
            if (loadPickMode(context) == PICK_MODE_AUTO) showBlockedHint(context, pkg)
            return
        }

        // 走到这里说明不拦：把显示前藏起来的弹窗恢复可见
        restoreForNonBlocked(dialog)

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

    private fun writeLog(activity: Activity, pkg: String, message: String) {
        try {
            ConfigClient.log(activity.contentResolver, pkg, message)
        } catch (_: Throwable) {
        }
    }

    /** 带功能标记的日志：功能页只看自己的记录时靠这个前缀筛选。 */
    private fun writeLogFor(activity: Activity, pkg: String, feature: String, message: String) {
        try {
            ConfigClient.logFeature(activity.contentResolver, pkg, feature, message)
        } catch (_: Throwable) {
        }
    }

    /**
     * 拦截弹窗写模块日志。
     *
     * 这里只有 Context 没有 Activity，所以借当前应用的 contentResolver 写；
     * 同一个弹窗可能连续被判多次，用标题去重，避免一次拦截刷出一堆重复记录。
     */
    private fun logBlock(context: android.content.Context, pkg: String, message: String) {
        try {
            if (!blockLogOnce.add("$pkg:${message.hashCode()}")) return
            ConfigClient.logFeature(context.contentResolver, pkg, FEATURE_BLOCK, message)
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
        private val updateHooked = ConcurrentHashMap.newKeySet<String>()
        private val askedThisProcess = ConcurrentHashMap.newKeySet<String>()

        /** 版本伪装配置缓存（3 秒），避免每次查版本都读文件。 */
        @Volatile
        private var updateConfCache: Pair<String, Int>? = null

        @Volatile
        private var updateConfAt = 0L

        private val versionLogOnce = ConcurrentHashMap.newKeySet<String>()

        private val adLogOnce = ConcurrentHashMap.newKeySet<String>()

        /** 去广告开关缓存（1 秒）：网络 hook 调用频繁，不能每次都读文件。 */
        @Volatile
        private var adOnCache: Boolean? = null

        @Volatile
        private var adOnCacheAt = 0L

        /** 去广告调试（DNS 日志）开关缓存：DNS 回调每次网络请求都会触发。 */
        @Volatile
        private var adLogOnCache: Boolean? = null

        @Volatile
        private var adLogOnCacheAt = 0L
        private const val AD_FLAG_TTL = 1_000L

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

        /** 净化点选层的标记（模块自己的视图不参与规则匹配）。 */
        private const val PURIFY_PICK_TAG = "selfxposed_purify_pick"

        /** 净化扫描：延迟、轮数、最大遍历深度。限次避免死循环把目标应用拖卡。 */
        private const val PURIFY_SCAN_DELAY = 600L
        /** 界面存活期间按布局变化持续扫描的节流间隔。 */
        private const val PURIFY_LAYOUT_THROTTLE = 400L
        private const val PURIFY_SCAN_ROUNDS = 4
        private const val PURIFY_MAX_DEPTH = 24

        /** 首帧绘制前应用规则的次数（防"先出现再消失"的闪烁）。 */
        private const val PURIFY_PRE_DRAW_ROUNDS = 3

        /** 点选时候选控件最多保留多少个（按面积升序，防止超大界面卡顿）。 */
        private const val PURIFY_MAX_CANDIDATES = 40

        /** 控件小图的最大宽度（像素）。小图够认出就行，别占太多地方。 */
        private const val PURIFY_SHOT_MAX_WIDTH = 480

        /** 占屏超过这个百分比就当成"整块容器"，隐藏前先警告（防止整屏黑掉）。 */
        private const val PURIFY_LARGE_PERCENT = 70

        /** 点选层：长按工具条图标后，文字提示显示多久再淡出（毫秒）。 */
        private const val PURIFY_ICON_HINT_MS = 1500L

        /** 净化规则缓存（2 秒），避免每次界面变化都读盘。 */
        @Volatile
        private var purifyCache: List<PurifyRules.Rule>? = null

        @Volatile
        private var purifyCacheAt = 0L

        /** 上一次读取规则时的规则文件时间戳/长度，用于跳过没变化的重复读取。 */
        @Volatile
        private var purifyConfModified = Long.MIN_VALUE

        @Volatile
        private var purifyConfLength = Long.MIN_VALUE

        private const val PURIFY_RULES_TTL = 2_000L

        /** 已经装过净化监听的界面（弱引用，界面销毁后自动清掉），避免重复安装。 */
        private val purifyInstalled: MutableSet<Activity> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
        private val purifyInstalledLock = Any()

        /** 因「占屏过大」被跳过隐藏的规则：同一个规则每进程只记一次日志，避免刷屏。 */
        private val purifySkipLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** 净化生效 / 被跳过时写进模块日志的去重集合：每条规则每进程只写一次，免得刷屏。 */
        private val purifyHitsLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val purifySkipLogOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** 拦截弹窗写模块日志的去重集合：同一个弹窗只记一次。 */
        private val blockLogOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** 跳过原因：进程内累积后节流写盘，供模块的规则列表显示（null = 还没读过文件）。 */
        private val purifySkippedLock = Any()
        private var purifySkippedCache: MutableMap<String, String>? = null
        private var purifySkippedFlushScheduled = false

        /** 命中数：同样在内存累加后节流写盘，避免在帧回调里读写文件。 */
        private val purifyHitsLock = Any()
        private var purifyHitsCache: MutableMap<String, Int>? = null
        private var purifyHitsFlushScheduled = false

        /** 已经拍过小图的规则（文件名就是规则 id），避免每轮都做文件存在性检查。 */
        private val purifyShotTaken = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** 跳过原因合并写盘的延迟：太短会频繁写盘，太长则模块里看到得晚。 */
        private const val PURIFY_SKIPPED_FLUSH_MS = 1_200L

        /** 已经装过翻译的界面（弱引用，界面销毁后自动清掉）。 */
        private val translateInstalled: MutableSet<Activity> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
        private val translateInstalledLock = Any()

        /** 词库缓存（3 秒），避免每次界面变化都读盘。 */
        @Volatile
        private var translateDictCache: Map<String, String>? = null

        @Volatile
        private var translateDictCacheAt = 0L

        /** 翻译选项缓存（3 秒）：带危险符号的字符串是否跳过。 */
        @Volatile
        private var translateSkipSymbolsCache: Boolean? = null

        @Volatile
        private var translateSkipSymbolsCacheAt = 0L

        private const val TRANSLATE_DICT_TTL = 3_000L
        private const val TRANSLATE_SCAN_DELAY = 700L
        private const val TRANSLATE_SCAN_ROUNDS = 4
        private const val TRANSLATE_PRE_DRAW_ROUNDS = 3

        /** 翻译开关缓存（2 秒）：资源层 hook 每秒会被调用成百上千次，不能每次都读文件。 */
        @Volatile
        private var translateFlagCache: Boolean? = null

        @Volatile
        private var translateFlagCacheAt = 0L
        private const val TRANSLATE_FLAG_TTL = 2_000L

        /** 诊断：这个进程从字符串资源取了几次文字、翻成功几次、最多记录多少条样本。 */
        private val translateResourceCalls = java.util.concurrent.atomic.AtomicInteger(0)
        private val translateHit = java.util.concurrent.atomic.AtomicInteger(0)
        private val translateMiss = java.util.concurrent.atomic.AtomicInteger(0)
        private val translateSamples = ArrayList<String>()
        private const val TRANSLATE_DIAG_SAMPLES = 12
        private const val TRANSLATE_DIAG_DELAY = 6_000L

        /** 功能日志标签：与界面共用同一套定义，避免两边写不一致。 */
        private const val FEATURE_TRANSLATE = ConfigClient.F_TRANSLATE
        private const val FEATURE_PURIFY = ConfigClient.F_PURIFY
        private const val FEATURE_BLOCK = ConfigClient.F_BLOCK
        private const val FEATURE_AD = ConfigClient.F_AD
        private const val FEATURE_UPDATE = ConfigClient.F_UPDATE

        /** 绘制探测：调用计数与样本（只记录，不改行为）。 */
        private val drawProbeCalls = java.util.concurrent.atomic.AtomicInteger(0)
        private val drawSamples = ArrayList<String>()
        private const val DRAW_PROBE_MAX_CALLS = 600
        private const val DRAW_PROBE_SAMPLES = 60
        private const val DRAW_PROBE_DELAY = 8_000L

        /** Compose 语义树探测：抓到的语义树根、Compose 画字次数、样本上限与延迟。 */
        @Volatile
        private var composeSemanticsOwner: Any? = null
        private val composePaintCalls = java.util.concurrent.atomic.AtomicInteger(0)
        private const val COMPOSE_PROBE_SAMPLES = 40
        private const val COMPOSE_PROBE_DELAY = 9_000L

        /** 净化开关缓存（2 秒）：绘制路径每秒被调用上千次，不能每次都读文件。 */
        @Volatile
        private var purifyFlagCache: Boolean? = null

        @Volatile
        private var purifyFlagCacheAt = 0L

        /** 调试日志去重：同一个包 / 同一个界面每进程只记一次，避免刷屏。 */
        private val translateLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val translateVerboseLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    }
}
