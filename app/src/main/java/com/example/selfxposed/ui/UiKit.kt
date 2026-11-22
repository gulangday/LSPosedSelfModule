package com.example.selfxposed.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 手写界面的小工具箱：dp 换算、圆角背景、文本、卡片、返回按钮等。
 * 所有页面共用，避免每个页面各自重复实现。
 */
class Ui(val activity: Activity) {
    private val density: Float = activity.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Int = 18): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    fun label(
        text: String,
        sizeSp: Int,
        color: Int = Palette.ink,
        bold: Boolean = false
    ): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(color)
        textSize = sizeSp.toFloat()
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    fun card(background: Int = Palette.surface): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        this.background = rounded(background, 18)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    fun sectionTitle(text: String): TextView = label(text, 18, Palette.ink, true).apply {
        setPadding(dp(2), dp(14), dp(2), dp(10))
    }

    fun hint(text: String): TextView = label(text, 13, Palette.muted, false).apply {
        setPadding(dp(2), 0, dp(2), dp(12))
    }

    fun actionCard(title: String, subtitle: String, onClick: () -> Unit): View {
        val container = card()
        container.addView(label(title, 15, Palette.ink, true))
        container.addView(label(subtitle, 12, Palette.muted, false).apply {
            setPadding(0, dp(5), 0, 0)
        })
        pressable(container, onClick)
        return container
    }

    fun backRow(text: String, onClick: () -> Unit): TextView = label(text, 14, Palette.accent, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(11), dp(18), dp(11))
        background = GradientDrawable().apply {
            setColor(Palette.hero)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Palette.accentSoft)
        }
        elevation = dp(1).toFloat()
        pressable(this, onClick)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) }
    }

    fun chip(text: String, textColor: Int, background: Int): TextView = label(text, 11, textColor, true).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        this.background = rounded(background, 10)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
    }

    /**
     * 开关状态的标签形式：已开启（绿）/ 未开启（灰），点击切换。
     * onChange 返回 true 才更新显示（写入失败时保持原状态）。
     */
    fun statusChip(initial: Boolean, onChange: (Boolean) -> Boolean): TextView {
        var on = initial
        val view = chip(
            if (on) "已开启" else "未开启",
            if (on) Palette.success else Palette.muted,
            if (on) Palette.successSoft else Palette.surfaceMuted
        )
        // 放在横向行的右侧：去掉 chip 默认的上边距，保证宽度完整不被裁切
        view.layoutParams = LinearLayout.LayoutParams(-2, -2)
        fun render(checked: Boolean) {
            view.text = if (checked) "已开启" else "未开启"
            view.setTextColor(if (checked) Palette.success else Palette.muted)
            view.background = rounded(if (checked) Palette.successSoft else Palette.surfaceMuted, 10)
        }
        view.isClickable = true
        view.setOnClickListener {
            val next = !on
            if (onChange(next)) {
                on = next
                render(next)
            }
        }
        return view
    }

    fun icon(drawable: Drawable, sizeDp: Int): ImageView = ImageView(activity).apply {
        setImageDrawable(drawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun divider(): View = View(activity).apply {
        setBackgroundColor(Color.argb(18, 0, 0, 0))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        }
    }

    fun space(heightDp: Int): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(heightDp))
    }

    fun slider(max: Int, progress: Int, onProgress: (Int) -> Unit): SeekBar =
        SeekBar(activity).apply {
            this.max = max
            this.progress = progress.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    onProgress(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }

    fun checkbox(checked: Boolean, enabled: Boolean, description: String, onChange: (Boolean) -> Unit) =
        android.widget.CheckBox(activity).apply {
            isChecked = checked
            isEnabled = enabled
            isClickable = enabled
            contentDescription = description
            alpha = if (enabled) 1f else 0.38f
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    /**
     * 点击动画：按下缩小并变淡，抬起后回弹，回弹结束再执行动作。
     * 注意：这里用 OnTouchListener 接管了事件，长按必须自己检测，否则 setOnLongClickListener 不会触发。
     */
    fun pressable(view: View, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
        view.isClickable = true
        view.isFocusable = true
        val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        var longPressFired = false
        var downX = 0f
        var downY = 0f

        val longPress = Runnable {
            longPressFired = true
            onLongClick?.invoke()
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    downX = event.rawX
                    downY = event.rawY
                    v.animate().cancel()
                    v.animate().scaleX(0.985f).scaleY(0.985f).alpha(0.92f)
                        .setDuration(75).setInterpolator(DecelerateInterpolator()).start()
                    if (onLongClick != null) v.postDelayed(longPress, 500)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > slop ||
                        kotlin.math.abs(event.rawY - downY) > slop
                    ) {
                        v.removeCallbacks(longPress)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(180).setInterpolator(DecelerateInterpolator())
                        .withEndAction { if (!longPressFired) onClick() }.start()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                    true
                }

                else -> false
            }
        }
    }

    /** 设置页入口行：左标题+副标题，右箭头。 */
    fun entryRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Palette.surface, 18)
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = false
            isFocusable = false
        }
        texts.addView(label(title, 15, Palette.ink, true))
        texts.addView(label(subtitle, 12, Palette.muted, false).apply {
            setPadding(0, dp(5), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 22, Palette.muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
            isClickable = false
        })
        pressable(row, onClick)
        return row
    }

    /**
     * 自绘风格对话框，替代系统 AlertDialog，和页面观感一致。
     * cancelText 传 null 表示只有一个按钮；onCancel 传 null 时取消按钮只关闭对话框。
     */
    fun dialog(
        title: String,
        message: String,
        confirmText: String = "确定",
        cancelText: String? = "取消",
        cancelable: Boolean = true,
        onConfirm: () -> Unit = {},
        onCancel: (() -> Unit)? = null
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            isClickable = true
            setOnClickListener { if (cancelable) dialog.dismiss() }
        }
        val cardView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = rounded(Palette.surface, 20)
            elevation = dp(8).toFloat()
            isClickable = true
        }
        cardView.addView(label(title, 17, Palette.ink, true))
        cardView.addView(label(message, 13, Palette.muted, false).apply {
            setPadding(0, dp(10), 0, dp(18))
        })

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (cancelText != null) {
            val cancel = label(cancelText, 14, Palette.muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = rounded(Palette.background, 20)
            }
            pressable(cancel, {
                dialog.dismiss()
                onCancel?.invoke()
            })
            buttons.addView(cancel)
        }
        val confirm = label(confirmText, 14, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(Palette.accent, 20)
        }
        pressable(confirm, {
            dialog.dismiss()
            onConfirm()
        })
        buttons.addView(confirm, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        cardView.addView(buttons, LinearLayout.LayoutParams(-1, -2))

        wrapper.addView(cardView, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(wrapper)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setCancelable(cancelable)
        dialog.show()
    }

    /** 自绘列表选择框（替代 AlertDialog.setItems）；selectedIndex >= 0 时高亮当前项。 */
    fun listDialog(
        title: String,
        items: List<String>,
        selectedIndex: Int = -1,
        showCancel: Boolean = true,
        onPick: (Int) -> Unit,
        cancelText: String = "取消"
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val cardView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
            background = rounded(Palette.surface, 20)
            elevation = dp(8).toFloat()
            isClickable = true
        }
        cardView.addView(label(title, 16, Palette.ink, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        items.forEachIndexed { index, item ->
            val chosen = index == selectedIndex
            val row = label(
                if (chosen) "✓  $item" else item,
                15,
                if (chosen) Palette.accent else Palette.ink,
                chosen
            ).apply {
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(if (chosen) Palette.hero else Palette.background, 12)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            }
            pressable(row, {
                dialog.dismiss()
                onPick(index)
            })
            cardView.addView(row)
        }
        if (showCancel) {
            val cancel = label(cancelText, 14, Palette.muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(11), dp(16), dp(11))
            }
            pressable(cancel, { dialog.dismiss() })
            cardView.addView(cancel, LinearLayout.LayoutParams(-1, -2))
        } else {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        }

        wrapper.addView(cardView, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(wrapper)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /** 看图弹窗：点小图后放大显示，点空白处关闭。 */
    fun imageDialog(title: String, image: android.graphics.Bitmap) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val cardView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = rounded(Palette.surface, 18)
            elevation = dp(8).toFloat()
            isClickable = true
        }
        cardView.addView(label(title, 15, Palette.ink, true))
        cardView.addView(ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(image)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        cardView.addView(label("点一下关闭", 11, Palette.muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        wrapper.addView(cardView, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(wrapper)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    /**
     * 带状态标签的列表选择框：左边名称，右边圆角状态标签（已开启/未开启）。
     */
    fun statusListDialog(
        title: String,
        items: List<Triple<String, String, Boolean>>,
        onPick: (Int) -> Unit,
        cancelText: String = "取消"
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val cardView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
            background = rounded(Palette.surface, 20)
            elevation = dp(8).toFloat()
            isClickable = true
        }
        cardView.addView(label(title, 16, Palette.ink, true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        items.forEachIndexed { index, (name, status, on) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Palette.background, 12)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                isClickable = true
            }
            row.addView(
                label(name, 15, Palette.ink, false),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            val chipView = label(
                status,
                11,
                if (on) Palette.success else Palette.muted,
                true
            ).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = rounded(
                    if (on) Palette.successSoft else Palette.surfaceMuted,
                    12
                )
            }
            row.addView(chipView)
            pressable(row, {
                dialog.dismiss()
                onPick(index)
            })
            cardView.addView(row)
        }

        val cancel = label(cancelText, 14, Palette.muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        pressable(cancel, { dialog.dismiss() })
        cardView.addView(cancel, LinearLayout.LayoutParams(-1, -2))

        wrapper.addView(cardView, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(wrapper)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /** 需要 root 但当前是非 Root 模式时的置灰入口（明显不可用）。 */
    fun lockedCard(title: String, subtitle: String, tag: String = "需要 Root / Shizuku 模式"): View {
        val disabledInk = Color.rgb(158, 155, 165)
        val container = card(Palette.surfaceMuted)
        container.isEnabled = false
        container.isClickable = false
        container.addView(label(title, 15, disabledInk, true))
        container.addView(label(subtitle, 12, disabledInk, false).apply {
            setPadding(0, dp(5), 0, 0)
        })
        container.addView(label("🔒 $tag", 11, disabledInk, true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(Color.rgb(220, 219, 224), 10)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
        })
        return container
    }
}
