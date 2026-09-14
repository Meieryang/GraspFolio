package io.graspfolio.app

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Disposable physical-pen and surface regression fixture. Never opens or saves a document. */
class PenPreviewActivity : ComponentActivity() {
    internal lateinit var ink: StylusInkView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        ink = StylusInkView(this).apply { enabledForWriting = true; brushStyle = BrushStyle("pressure", Color.BLACK, 5f) }
        root.addView(ink, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfff3ede4.toInt()) }
        val message = TextView(this).apply { text = "书写测试页 · 不保存笔迹" }
        val options = LinearLayout(this)
        options.addView(Button(this).apply { text = "稳定跟手"; setOnClickListener {
            ink.predictionMode = if (ink.predictionMode == PredictionMode.STABLE) PredictionMode.STRONG else PredictionMode.STABLE
            text = ink.predictionMode.label
        } })
        options.addView(Button(this).apply { text = "预测：开"; setOnClickListener { ink.predictionEnabled = !ink.predictionEnabled; text = if (ink.predictionEnabled) "预测：开" else "预测：关" } })
        options.addView(Button(this).apply { text = "前缓冲：开"; setOnClickListener { ink.frontBufferEnabled = !ink.frontBufferEnabled; text = if (ink.frontBufferEnabled) "前缓冲：开" else "前缓冲：关" } })
        options.addView(Button(this).apply { text = "清空"; setOnClickListener { ink.cancelStroke(); ink.strokes = emptyList() } })
        controls.addView(options); controls.addView(message)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        ink.onDiagnostics = { message.text = "测试页 · 不保存\n$it" }
        ink.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            ink.placements = listOf(PagePlacement(0, 0f, 0f, ink.width.toFloat(), ink.height.toFloat(), 1f))
        }
        setContentView(root)
    }
}
