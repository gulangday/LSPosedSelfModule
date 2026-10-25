package com.example.selfxposed

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 目标应用内的自绘底部弹窗。
 *
 * 为什么不用 Toast：系统 Toast 的时长与样式不可控（Android 12 起还限制后台 Toast），
 * 无法实现“显示 5 秒 + 淡出 1 秒”。这里把 View 直接挂在当前 Activity 的 DecorView 上，
 * 既不需要悬浮窗权限，也天然跟随 Activity 生命周期。
 *
 * 计时规则（对应 README 的约定）：
 * - 计时按进程 + 包名各一份，只在首次弹出时启动；
 * - Activity 切换时把同一个 View 迁移到新的 DecorView，不重置剩余时间；
 * - 每个包同时只存在一个弹窗，重复调用只会重挂载，不会叠加。
 */
object PopupController {
    private const val MIN_SHOW_MS = 500
    private const val MAX_SHOW_MS = 30_000
    private const val MAX_FADE_MS = 5_000
    private const val FADE_IN_MS = 120L
    private const val MAX_TEXT_WIDTH_DP = 280
    private const val BOTTOM_MARGIN_DP = 96
    private const val SIDE_MARGIN_DP = 24

    private val handler = Handler(Looper.getMainLooper())
    private val states = HashMap<String, State>()

    private class State(val text: String, val showMs: Long, val fadeMs: Long) {
        var view: WeakReference<View>? = null
        var fadeStarted = false
        var finished = false
        var fadeJob: Runnable? = null
    }

    fun show(
        activity: Activity,
        key: String,
        text: String,
        showMs: Int,
        fadeMs: Int,
        restart: Boolean = false
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(activity, key, text, showMs, fadeMs, restart) }
            return true
        }
        synchronized(this) {
            val active = states[key]
            if (active != null && !active.finished && !active.fadeStarted && !restart) {
                // 正在显示：只迁移到新的 Activity，保留原本的剩余时间。
                return attach(activity, active)
            }
            if (active != null) finish(key, active)

            val state = State(
                text.trim().ifBlank { "你好" },
                showMs.toLong().coerceIn(MIN_SHOW_MS.toLong(), MAX_SHOW_MS.toLong()),
                fadeMs.toLong().coerceIn(0L, MAX_FADE_MS.toLong())
            )
            states[key] = state
            val attached = attach(activity, state)

            val job = Runnable { startFade(key, state) }
            state.fadeJob = job
            handler.postDelayed(job, state.showMs)
            return attached
        }
    }

    fun isShowing(key: String): Boolean = synchronized(this) {
        val state = states[key] ?: return false
        !state.finished && !state.fadeStarted
    }

    /** 立即移除指定弹窗（预览时用于重新开始计时）。 */
    fun dismiss(key: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { dismiss(key) }
            return
        }
        synchronized(this) {
            states[key]?.let { finish(key, it) }
        }
    }

    private fun attach(activity: Activity, state: State): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        var view = state.view?.get()
        val firstAttach = view == null
        if (view == null) {
            view = buildView(activity, state.text)
            state.view = WeakReference(view)
        }
        val currentParent = view.parent as? ViewGroup
        if (currentParent === decor) return true

        currentParent?.removeView(view)
        decor.addView(view, layoutParams(activity))
        if (firstAttach) {
            view.alpha = 0f
            view.animate().cancel()
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        } else {
            view.alpha = 1f
        }
        return true
    }

    private fun startFade(key: String, state: State) {
        if (state.finished) return
        state.fadeStarted = true
        val view = state.view?.get()
        if (view == null) {
            finish(key, state)
            return
        }
        if (state.fadeMs <= 0L) {
            finish(key, state)
            return
        }
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(state.fadeMs)
            .withEndAction { finish(key, state) }
            .start()
    }

    private fun finish(key: String, state: State) {
        if (state.finished) return
        state.finished = true
        state.fadeJob?.let { handler.removeCallbacks(it) }
        val view = state.view?.get()
        (view?.parent as? ViewGroup)?.removeView(view)
        state.view = null
        if (states[key] === state) states.remove(key)
    }

    private fun buildView(activity: Activity, text: String): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val content = TextView(activity).apply {
            this.text = text
            setTextColor(Color.BLACK)
            textSize = 15f
            gravity = Gravity.CENTER
            // 不设 ellipsize：在 WRAP_CONTENT + maxWidth 的组合下，
            // ellipsize 会让 TextView 按单行测量，长文本被裁掉而不是换行。
            isSingleLine = false
            maxLines = 8
            setLineSpacing(0f, 1.12f)
            maxWidth = dp(MAX_TEXT_WIDTH_DP)
            includeFontPadding = false
            isClickable = false
            isFocusable = false
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(24, 0, 0, 0))
            }
            elevation = dp(8).toFloat()
            // 不拦截触摸：未消费的事件会继续传给下面的界面。
            isClickable = false
            isFocusable = false
            addView(content, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun layoutParams(activity: Activity): FrameLayout.LayoutParams {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        return FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(BOTTOM_MARGIN_DP)
            leftMargin = dp(SIDE_MARGIN_DP)
            rightMargin = dp(SIDE_MARGIN_DP)
        }
    }
}
