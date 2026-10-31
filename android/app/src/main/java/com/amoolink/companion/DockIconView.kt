package com.amoolink.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Dock 自绘图标(暗夜链路):线性房子(首页)/ 文件夹(文件管理)。
 * 选中 = 信号青加粗 + 淡青填充,未选中 = 灰描边;比 unicode 字符干净得多。
 */
class DockIconView(context: Context, private val kind: Kind) : View(context) {

    enum class Kind { HOME, FILES }

    private val dens = context.resources.displayMetrics.density

    private fun dpf(v: Float) = v * dens

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val s = minOf(w, h) * 0.62f
        val l = (w - s) / 2
        val t = (h - s) / 2
        val r = l + s
        val b = t + s
        val cx = w / 2f

        val color = if (active) Ui.ACCENT else Ui.TEXT_3
        stroke.color = color
        stroke.strokeWidth = dpf(if (active) 2.4f else 2f)
        fill.color = color

        when (kind) {
            Kind.HOME -> {
                // 屋顶
                val roof = Paint(stroke)
                canvas.drawLine(l, t + s * 0.52f, cx, t, roof)
                canvas.drawLine(cx, t, r, t + s * 0.52f, roof)
                // 房体(圆角)
                val bodyLeft = l + s * 0.10f
                val bodyRight = r - s * 0.10f
                val bodyTop = t + s * 0.42f
                canvas.drawRoundRect(bodyLeft, bodyTop, bodyRight, b, dpf(3f), dpf(3f), stroke)
                // 门:选中实心,未选中描边小矩形
                val doorW = s * 0.20f
                val doorH = s * 0.30f
                if (active) {
                    canvas.drawRoundRect(cx - doorW / 2, b - doorH, cx + doorW / 2, b - dpf(1.5f), dpf(2f), dpf(2f), fill)
                } else {
                    stroke.strokeWidth = dpf(1.8f)
                    canvas.drawRoundRect(cx - doorW / 2, b - doorH, cx + doorW / 2, b - dpf(1.5f), dpf(2f), dpf(2f), stroke)
                }
            }

            Kind.FILES -> {
                // 背板:选中淡青填充
                if (active) {
                    fill.color = android.graphics.Color.parseColor("#1E3A38")
                    canvas.drawRoundRect(l, t + s * 0.26f, r, b, dpf(4f), dpf(4f), fill)
                    fill.color = color
                }
                // 文件夹舌片
                val tab = Paint(stroke)
                tab.strokeWidth = stroke.strokeWidth
                val tabPath = android.graphics.Path().apply {
                    moveTo(l, t + s * 0.34f)
                    lineTo(l, t + s * 0.10f)
                    lineTo(l + s * 0.38f, t + s * 0.10f)
                    lineTo(l + s * 0.50f, t + s * 0.34f)
                }
                canvas.drawPath(tabPath, tab)
                // 夹体(圆角)
                canvas.drawRoundRect(l, t + s * 0.30f, r, b, dpf(4f), dpf(4f), stroke)
            }
        }
    }
}
