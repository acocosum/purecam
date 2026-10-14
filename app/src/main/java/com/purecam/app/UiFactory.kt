package com.purecam.app

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * UI 构建辅助类：把「动态创建 View」的细节从 MainActivity 中剥离出去，
 * 让 Activity 只关注状态机与相机逻辑。
 *
 * 全部使用系统控件（ImageButton / TextView / SeekBar），不引入 Material 依赖，
 * 从而保持 APK 体积最小、构建依赖最少。
 */
object UiFactory {

    /** dp → px */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    /** 纯色圆角背景 */
    fun solidDrawable(context: Context, @ColorInt color: Int, cornerDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, cornerDp).toFloat()
            setColor(color)
        }

    /** 面板中的一个图标按钮（48dp） */
    fun createImageButton(
        context: Context,
        iconRes: Int,
        contentDescription: String,
        sizeDp: Int = 48
    ): ImageButton = ImageButton(context).apply {
        val size = dp(context, sizeDp)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            val m = dp(context, 4)
            setMargins(m, m, m, m)
        }
        setImageResource(iconRes)
        this.contentDescription = contentDescription
        background = solidDrawable(context, COLOR_BUTTON_BG, 24)
        setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
        scaleType = ImageView.ScaleType.FIT_CENTER
        // 默认白色图标
        UiFactory.setIconTint(this, COLOR_ICON_NORMAL)
        isFocusable = true
    }

    /**
     * 面板中的一个文字按钮（用于显示状态的按钮，如「AF-C 连续」「校准图」）。
     *
     * 宽高可分别指定：文字较长的按钮（如「AF-C 连续」）需要更宽而不是更高，
     * 否则行高会被撑高，二级面板就会把一级菜单挤出屏幕。
     */
    fun createTextButton(
        context: Context,
        text: String,
        widthDp: Int = 48,
        heightDp: Int = 48
    ): TextView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp)).apply {
            val m = dp(context, 4)
            setMargins(m, m, m, m)
        }
        gravity = Gravity.CENTER
        this.text = text
        setTextColor(COLOR_ICON_NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = solidDrawable(context, COLOR_BUTTON_BG, 24)
    }

    /** 面板中的文本标签 */
    fun createLabel(context: Context, text: String): TextView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val m = dp(context, 6)
            setMargins(m, m, m, m)
        }
        this.text = text
        setTextColor(COLOR_TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    /**
     * 修改 ImageButton 的图标着色，用于表达「开关已开启 / 关闭」。
     * 使用 SRC_IN 模式，只影响图标的非透明像素。
     */
    fun setIconTint(button: ImageButton, @ColorInt color: Int) {
        button.drawable?.mutate()?.let { drawable ->
            drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            drawable.invalidateSelf()
        }
        button.invalidate()
    }

    /** 修改文字按钮的选中态颜色 */
    fun setTextSelected(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) COLOR_ACCENT else COLOR_ICON_NORMAL)
        (view.background as? GradientDrawable)?.setColor(
            if (selected) COLOR_BUTTON_BG_SELECTED else COLOR_BUTTON_BG
        )
        view.invalidate()
    }

    // ---------------------------------------------------------------- 配色
    @ColorInt const val COLOR_ACCENT = 0xFF40C4FF.toInt()          // 选中 / 激活：青蓝
    @ColorInt const val COLOR_ICON_NORMAL = 0xFFFFFFFF.toInt()     // 常态图标：白
    @ColorInt const val COLOR_TEXT_SECONDARY = 0xB3FFFFFF.toInt()  // 次要文字：70% 白
    @ColorInt const val COLOR_BUTTON_BG = 0x66000000.toInt()       // 按钮底：黑色 40%
    @ColorInt const val COLOR_BUTTON_BG_SELECTED = 0xCC123A50.toInt() // 选中按钮底
    @ColorInt const val COLOR_PANEL_BG = 0x99000000.toInt()        // 面板底：黑色 60%
    @ColorInt val COLOR_TRANSPARENT: Int = Color.TRANSPARENT
}
