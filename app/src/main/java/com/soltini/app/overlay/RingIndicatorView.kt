package com.soltini.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class RingState { IDLE, LISTENING, SPEAKING, THINKING, SLEEPING }

/**
 * Rotating wireframe-sphere orb, ported from the user-supplied canvas
 * reference (index.html / style.css / script.js — a spinning 3-ring
 * wireframe globe drawn with rotation matrices + pseudo-perspective).
 *
 * The original web version used an offscreen <canvas> with a persistent
 * translucent black fill each frame to create a motion-trail effect.
 * That trick can't be reused here because this View sits on a fully
 * transparent overlay window (see OverlayService: setBackgroundColor
 * TRANSPARENT + PixelFormat.TRANSLUCENT) — painting any black rectangle,
 * even a faint one, would show up as a dark box over whatever app is
 * behind the orb. Instead, each frame is drawn clean (no fill), and the
 * three overlapping/offset "layers" from the original algorithm (the
 * `e in 0..2` loop) alone are enough to reproduce the same braided,
 * layered wireframe look.
 */
class RingIndicatorView(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Raw mic/speaker amplitude (0f..~1f), same contract as before. */
    var amplitude: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var state: RingState = RingState.IDLE
        set(value) {
            field = value
            pulseAnimator?.cancel()
            when (value) {
                RingState.IDLE -> {
                    saturation = 0.55f
                    baseAlpha = 0.6f
                    rotationSpeed = 1f
                    invalidate()
                }
                RingState.SLEEPING -> {
                    // Dim, near-grayscale, slow pulse — tap to wake
                    saturation = 0.1f
                    rotationSpeed = 0.3f
                    pulseAnimator = ValueAnimator.ofFloat(0.2f, 0.4f, 0.2f).apply {
                        duration = 3000
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        addUpdateListener {
                            baseAlpha = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                }
                RingState.LISTENING -> startPulse(sat = 0.75f, speed = 1.3f)
                RingState.SPEAKING -> startPulse(sat = 0.85f, speed = 1.7f)
                RingState.THINKING -> startPulse(sat = 0.8f, speed = 1.5f)
            }
        }

    private var saturation = 0.55f
    private var baseAlpha = 0.6f
    private var rotationSpeed = 1f
    private var pulseAnimator: ValueAnimator? = null

    private fun startPulse(sat: Float, speed: Float) {
        saturation = sat
        rotationSpeed = speed
        pulseAnimator = ValueAnimator.ofFloat(0.6f, 1.0f, 0.6f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                baseAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    init {
        saturation = 0.55f
        baseAlpha = 0.6f
    }

    // ---- Wireframe sphere geometry (direct port of script.js's `p` array) ----
    private val ringCount = 80 // "max" in the original script
    private val basePoints: Array<FloatArray> = buildBasePoints(ringCount)
    private var tick = 0f

    private fun buildBasePoints(max: Int): Array<FloatArray> {
        val circleA = Array(max) { FloatArray(3) }
        var r = 0f
        val step = (Math.PI * 2 / max).toFloat()
        for (i in 0 until max) {
            circleA[i][0] = cos(r)
            circleA[i][1] = sin(r)
            circleA[i][2] = 0f
            r += step
        }
        val pts = ArrayList<FloatArray>(max * 3)
        pts.addAll(circleA)
        for (i in 0 until max) pts.add(floatArrayOf(0f, circleA[i][0], circleA[i][1]))
        for (i in 0 until max) pts.add(floatArrayOf(circleA[i][1], 0f, circleA[i][0]))
        return pts.toTypedArray()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (cx == 0f || cy == 0f) {
            invalidate()
            return
        }

        val max = ringCount
        val fitScale = min(width, height) / 400f // original canvas was 400x400
        val ampBoost = 1f + (amplitude.coerceIn(0f, 1f) * 0.6f)
        val baseScale = 120f * fitScale * ampBoost

        var tim = tick / 5f

        for (e in 0 until 3) {
            tim *= 1.7f
            val depthFactor = 1f - e / 3f

            val a1 = tim / 59f
            val yp = cos(a1)
            val yp2 = sin(a1)
            val a2 = tim / 23f
            val xp = cos(a2)
            val xp2 = sin(a2)

            val projected = arrayOfNulls<FloatArray>(basePoints.size)
            for (i in basePoints.indices) {
                val src = basePoints[i]
                val x0 = src[0]; val y0 = src[1]; val z0 = src[2]

                val y1 = y0 * yp + z0 * yp2
                val z1 = y0 * yp2 - z0 * yp
                val x1 = x0 * xp + z1 * xp2
                var z = x0 * xp2 - z1 * xp
                z = 2f.pow(z * depthFactor)
                val x = x1 * z
                val y = y1 * z
                projected[i] = floatArrayOf(x, y, z)
            }

            val s = depthFactor * baseScale
            val lineAlpha = (baseAlpha * 70).toInt().coerceIn(8, 255)

            for (d in 0 until 3) {
                for (a in 0 until max) {
                    val b = projected[d * max + a]!!
                    val c = projected[((a + 1) % max) + d * max]!!

                    val hue = (a.toFloat() / max * 360f)
                    linePaint.color = Color.HSVToColor(
                        lineAlpha,
                        floatArrayOf(hue, saturation, 1f)
                    )
                    linePaint.strokeWidth = (6f.pow(b[2])).coerceIn(0.5f, 14f) * fitScale

                    canvas.drawLine(
                        b[0] * s + cx, b[1] * s + cy,
                        c[0] * s + cx, c[1] * s + cy,
                        linePaint
                    )
                }
            }
        }

        tick += rotationSpeed * (1f + amplitude.coerceIn(0f, 1f))
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}
