package com.purecam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/**
 * 校准图案：一组「标准正方形网格 + 同心圆 + 中心十字」。
 *
 * 用途：把手机画面送到 Switch 2（或采集卡）后，如果链路中存在非等比缩放，
 * 这里的正方形会变成矩形、圆会变成椭圆。观察者据此判断失真方向：
 *   - 图案被「压扁」（宽 > 高）→ 需要增大垂直补偿（>1.00）
 *   - 图案被「拉高」（高 > 宽）→ 需要减小垂直补偿（<1.00）
 *
 * 网格单元严格取正方形（以短边为基准），因此任何非等比失真都能一眼看出。
 */
class CalibrationOverlay(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40C4FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f

        // 网格单元：以短边为基准的正方形
        val cell = min(w, h) / 8f
        val cols = (w / cell).toInt()
        val rows = (h / cell).toInt()
        val gridW = cols * cell
        val gridH = rows * cell
        val left = cx - gridW / 2f
        val top = cy - gridH / 2f

        for (i in 0..cols) {
            val x = left + i * cell
            canvas.drawLine(x, top, x, top + gridH, linePaint)
        }
        for (j in 0..rows) {
            val y = top + j * cell
            canvas.drawLine(left, y, left + gridW, y, linePaint)
        }

        // 中心正方形（严格正方）：如果链路有非等比缩放，它会变成矩形
        canvas.drawRect(cx - cell, cy - cell, cx + cell, cy + cell, accentPaint)

        // 同心圆：非等比缩放时会变成椭圆
        canvas.drawCircle(cx, cy, cell * 0.45f, boldPaint)
        canvas.drawCircle(cx, cy, cell * 0.9f, linePaint)

        canvas.drawText("正方形 / 圆", left + 12f, top + 46f, textPaint)
    }
}
