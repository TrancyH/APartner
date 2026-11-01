package com.amoolink.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/**
 * 勾选圆:选中 = 实心圆 + Path 画的勾,未选 = 细描边圆。
 *
 * 为什么不用「✓」字符:勾号是字形,不同厂商字体的字面大小/基线不同,同一份 22dp
 * 的 TextView 在小米和一加上视觉大小能差 2~3dp;而且字体勾在放大后边缘会糊。
 * Path 画出来的勾在任何尺寸、任何字号设置下都一致。
 */
class CheckView(context: Context) : View(context) {

    /** 选中时的圆底色。 */
    var fillColor: Int = Ui.ACCENT

    /** 勾的颜色。 */
    var markColor: Int = Color.parseColor("#0A2B29")

    /** 未选时的圆描边色。 */
    var ringColor: Int = Ui.STROKE

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                // 选中时轻微回弹一下(纯 View 动画,不引库)
                scaleX = 0.72f
                scaleY = 0.72f
                animate().scaleX(1f).scaleY(1f).setDuration(150)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
            }
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        if (isChecked) {
            fill.color = fillColor
            canvas.drawCircle(cx, cy, r, fill)
            tick.color = markColor
            tick.strokeWidth = r * 0.34f
            path.reset()
            path.moveTo(w * 0.30f, h * 0.52f)
            path.lineTo(w * 0.44f, h * 0.66f)
            path.lineTo(w * 0.71f, h * 0.35f)
            canvas.drawPath(path, tick)
        } else {
            ring.color = ringColor
            ring.strokeWidth = r * 0.12f
            canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        }
    }
}
