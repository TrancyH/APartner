package com.amoolink.companion

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 暗夜链路设计系统(程序化,零外部依赖):
 * 深蓝灰底 + 青蓝信号色;卡片/胶囊/磁块/按钮/排版刻度全部由此产出,
 * 两页(MainActivity/FilesActivity)共用,保证视觉一致性。
 */
object Ui {

    // ── 色板 ────────────────────────────────────────────────────────
    val BG = Color.parseColor("#0E1116") // 页面底
    val SURFACE = Color.parseColor("#161B23") // 卡片
    val SURFACE_HIGH = Color.parseColor("#1D242F") // 浮层/操作栏
    val STROKE = Color.parseColor("#232C38") // 描边
    val TEXT = Color.parseColor("#E6EBF2") // 主文字
    val TEXT_2 = Color.parseColor("#8B95A5") // 次文字
    val TEXT_3 = Color.parseColor("#5A6472") // 弱文字
    val ACCENT = Color.parseColor("#4FD1C5") // 信号青
    val ACCENT_2 = Color.parseColor("#3B82F6") // 信号蓝
    val OK = Color.parseColor("#34D399")
    val DANGER = Color.parseColor("#F87171")

    /** 文件磁块(OEM 式按类型着色)。 */
    val TILE_IMAGE = Color.parseColor("#2F7D5F")
    val TILE_VIDEO = Color.parseColor("#6D5BB8")
    val TILE_AUDIO = Color.parseColor("#B58A2C")
    val TILE_DOC = Color.parseColor("#3B6FB8")
    val TILE_APK = Color.parseColor("#B85B5B")
    val TILE_ARCHIVE = Color.parseColor("#8A6D4F")
    val TILE_OTHER = Color.parseColor("#3A4656")

    // ── 排版刻度 ────────────────────────────────────────────────────
    val DISPLAY = Typeface.create("sans-serif-thin", Typeface.NORMAL) // 大数字/大状态
    val TITLE = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BODY = Typeface.create("sans-serif", Typeface.NORMAL)

    // ── 基础 ────────────────────────────────────────────────────────

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun radius(context: Context, v: Int): Float = dp(context, v).toFloat()

    fun card(context: Context, bgColor: Int = SURFACE, corner: Int = 14, strokeColor: Int = STROKE, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius(context, corner)
            setColor(bgColor)
            if (strokeWidth > 0) setStroke(dp(context, strokeWidth), strokeColor)
        }

    fun cardGradientStroke(context: Context, bgColor: Int = SURFACE, corner: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius(context, corner)
            setColor(bgColor)
            setOrientation(GradientDrawable.Orientation.LEFT_RIGHT)
            setColors(intArrayOf(ACCENT, ACCENT_2))
            // 渐变描边:用双层法简化为单色青描边 + 内容内衬
            setStroke(dp(context, 1), ACCENT)
        }

    fun pill(context: Context, bgColor: Int, corner: Int = 999): GradientDrawable =
        card(context, bgColor, corner, strokeColor = Color.TRANSPARENT, strokeWidth = 0)

    fun tile(context: Context, bgColor: Int, corner: Int = 10): GradientDrawable =
        card(context, bgColor, corner, strokeColor = Color.TRANSPARENT, strokeWidth = 0)

    fun ripple(context: Context, content: GradientDrawable, pressAlpha: Int = 0x24): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(pressAlpha, 0x4F, 0xD1, 0xC5)),
            content,
            null,
        )

    // ── 文本 ────────────────────────────────────────────────────────

    fun text(context: Context, s: CharSequence, size: Int, color: Int, typeface: Typeface = BODY): TextView =
        TextView(context).apply {
            text = s
            textSize = size.toFloat()
            setTextColor(color)
            this.typeface = typeface
        }

    // ── 按钮 ────────────────────────────────────────────────────────

    /** 主按钮:青底深字(信号色实心)。 */
    fun primaryButton(context: Context, label: String): Button =
        Button(context).apply {
            text = label
            textSize = 14f
            typeface = TITLE
            setTextColor(Color.parseColor("#0A2B29"))
            background = ripple(context, pill(context, ACCENT, 12))
            setAllCaps(false)
            stateListAnimator = null
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }

    /** 次按钮:面底描边浅字。 */
    fun ghostButton(context: Context, label: String, danger: Boolean = false): Button =
        Button(context).apply {
            text = label
            textSize = 13f
            setTextColor(if (danger) DANGER else TEXT)
            background = ripple(context, card(context, SURFACE_HIGH, 12))
            setAllCaps(false)
            stateListAnimator = null
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
        }

    // ── 行/分节 ─────────────────────────────────────────────────────

    fun sectionHeader(context: Context, s: String): TextView =
        text(context, s, 12, TEXT_3, TITLE).apply {
            letterSpacing = 0.08f
            setPadding(dp(context, 4), 0, 0, 0)
        }

    /** 设置行:图标 + 标签 + 右侧状态/箭头;整行可点带涟漪。返回 (行, 右态文本引用)。 */
    fun settingRow(
        context: Context,
        glyph: String,
        label: String,
        right: String,
        onClick: () -> Unit,
    ): Pair<LinearLayout, TextView> {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = ripple(context, card(context, Color.TRANSPARENT, 10, strokeWidth = 0))
            setOnClickListener { onClick() }
        }
        line.addView(
            text(context, glyph, 16, ACCENT).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(context, 30), ViewGroupHelpers.wrap()),
        )
        line.addView(
            text(context, label, 14, TEXT),
            LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f).apply { marginStart = dp(context, 6) },
        )
        val rightView = text(context, right, 12, TEXT_3)
        line.addView(rightView)
        return line to rightView
    }

    fun post(view: View, action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }
}

/** ViewGroup 尺寸常量小助手(避免到处写 MATCH_PARENT/WRAP_CONTENT)。 */
object ViewGroupHelpers {
    fun wrap(): Int = ViewGroup.LayoutParams.WRAP_CONTENT
}
