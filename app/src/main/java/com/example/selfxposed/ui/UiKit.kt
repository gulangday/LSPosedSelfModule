package com.example.selfxposed.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
        elevation = dp(1).toFloat()
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
        container.isClickable = true
        container.isFocusable = true
        container.setOnClickListener { onClick() }
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
        isClickable = true
        isFocusable = true
        elevation = dp(1).toFloat()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) }
    }

    fun chip(text: String, textColor: Int, background: Int): TextView = label(text, 11, textColor, true).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        this.background = rounded(background, 10)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
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
}
