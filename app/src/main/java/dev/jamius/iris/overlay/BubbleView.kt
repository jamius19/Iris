/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.PathParser
import androidx.core.graphics.withSave
import dev.jamius.iris.R
import kotlin.math.roundToInt

/** What the bubble shows. */
enum class BubbleStatus {
    /** Idle circle (contrasts with the theme) with the logo's waveform (drawn in when the bubble shows). */
    IDLE,

    /** Control circle with a ✕ (cancel); the overlay shows the pill below it. */
    LISTENING,

    /** Control circle with a spinner (finalizing, cleanup, insertion). */
    WORKING,

    /** Green, with a tick. */
    SUCCESS,

    /** Red, with an exclamation mark. */
    FAILURE,
}

/**
 * The bubble: a filled circle with an icon drawn per [status]; a status change morphs colour and icon.
 * The view is larger than the circle by [MARGIN_DP] on each side (touch target and shadow room), plus
 * [shadowRoomPx] below, so the shadow, which falls downward, isn't cut by the window's bottom edge.
 */
internal class BubbleView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val radius = RADIUS_DP * density
    val sizePx = (2 * (radius + MARGIN_DP * density)).roundToInt()

    /** Extra view height below [sizePx]; [sizePx] stays the button's size for placement. */
    val shadowRoomPx = (SHADOW_ROOM_DP * density).roundToInt()

    /** Diameter of the visible circle, and its offset from the view's top/left edge (the margin). */
    val circlePx = (2 * radius).roundToInt()
    val circleInsetPx = (sizePx - circlePx) / 2

    private var idleColor = 0
    private var controlColor = 0
    private var successColor = 0
    private var failureColor = 0
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val close = icon(R.drawable.ic_close)
    private val check = icon(R.drawable.ic_check)
    private val exclamation = icon(R.drawable.ic_exclamation)

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = SPINNER_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val spinnerBounds = RectF().apply {
        val center = sizePx / 2f
        val r = (ICON_DP - SPINNER_STROKE_DP) * density / 2
        set(center - r, center - r, center + r, center + r)
    }

    // The waveform from iris_logo.xml, scaled to WAVE_WIDTH_DP wide (with its stroke) and centred in the view.
    private val wave = PathParser.createPathFromPathData(WAVE_PATH)
    private val waveScale: Float

    init {
        val bounds = RectF().also { wave.computeBounds(it, true) }
        waveScale = WAVE_WIDTH_DP * density / (bounds.width() + WAVE_STROKE)
        wave.transform(
            Matrix().apply {
                setTranslate(-bounds.centerX(), -bounds.centerY())
                postScale(waveScale, waveScale)
                postTranslate(sizePx / 2f, sizePx / 2f)
            }
        )
    }

    private val waveMeasure = PathMeasure(wave, false) // One contour. After the transform above.
    private val waveSegment = Path() // Reused per frame.
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = WAVE_STROKE * waveScale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** How much of the idle waveform is drawn, along its length (0..1); [drawWave] animates it. */
    private var waveDrawn = 0f
    private var waveAnimator: ValueAnimator? = null

    /** The last [drawWave] call drew the wave in: the bubble is shown, not hiding. */
    private var waveShown = false

    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    /**
     * The morph draws [from] -> [to] at progress [t] (1 = only [to] shows). [to] is [status], except while a
     * [reversed] morph runs back to [from] (the status was set back mid-morph).
     */
    private var from = BubbleStatus.IDLE
    private var to = BubbleStatus.IDLE
    private var t = 1f
    private var reversed = false
    private val morph = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = MORPH_MS
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        doOnEnd {
            if (reversed) {
                to = from
                t = 1f
                reversed = false
            }
            updateAnimations() // Stops the spinner once it has morphed out.
        }
    }

    var status = BubbleStatus.IDLE
        set(value) {
            if (field == value) return
            field = value
            contentDescription = context.getString(description(value))
            when {
                !isAttachedToWindow -> {
                    morph.cancel()
                    to = value
                    t = 1f
                }
                // Set back to the status being morphed away from: run the morph back from where it is.
                morph.isRunning && value == (if (reversed) to else from) -> {
                    reversed = !reversed
                    morph.reverse()
                }
                else -> {
                    // Mid-morph, morph on from whichever status dominates now.
                    if (t >= 0.5f) from = to
                    to = value
                    reversed = false
                    morph.start()
                    // Back at rest while shown: the wave draws itself in again (instead of growing in, see onDraw).
                    if (value == BubbleStatus.IDLE && waveShown) {
                        waveDrawn = 0f
                        drawWave(true)
                    }
                }
            }
            updateAnimations()
            invalidate()
        }

    init {
        loadColors()
        contentDescription = context.getString(description(status))
        elevation = 5 * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val center = sizePx / 2
                val r = radius.roundToInt()
                outline.setOval(center - r, center - r, center + r, center + r)
            }
        }
    }

    /**
     * Draws the waveform in, left to right, or un-draws it, from where it is. Only animates at rest: in any other
     * status it jumps, so a re-show mid-session doesn't replay it and a hide while listening doesn't draw one.
     */
    fun drawWave(drawn: Boolean) {
        waveShown = drawn
        waveAnimator?.cancel()
        val target = if (drawn) 1f else 0f
        if (status != BubbleStatus.IDLE) {
            waveDrawn = target
            return
        }
        waveAnimator = ValueAnimator.ofFloat(waveDrawn, target).apply {
            duration = if (drawn) WAVE_IN_MS else WAVE_OUT_MS
            interpolator = WAVE_EASE
            addUpdateListener {
                waveDrawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Re-reads the colours, e.g. after a dark mode switch. */
    fun onConfigurationChanged() {
        loadColors()
        invalidate()
    }

    private fun loadColors() {
        idleColor = context.getColor(R.color.bubble_idle)
        controlColor = context.getColor(R.color.bubble_control)
        successColor = context.getColor(R.color.bubble_success)
        failureColor = context.getColor(R.color.bubble_failure)
        val controlIcon = context.getColor(R.color.bubble_control_icon)
        spinnerPaint.color = controlIcon
        close.setTint(controlIcon)
        wavePaint.color = context.getColor(R.color.bubble_idle_icon)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx + shadowRoomPx)
    }

    override fun onDraw(canvas: Canvas) {
        val center = sizePx / 2f
        // The new colour grows from the centre over the old one, so no blended (muddy) colour ever shows.
        if (t < 1f) {
            fillPaint.color = fill(from)
            canvas.drawCircle(center, center, radius, fillPaint)
        }
        fillPaint.color = fill(to)
        // Done at t = 0.8, so the old colour's last thin ring closes at speed instead of lingering as an outline in
        // the curve's slow tail.
        canvas.drawCircle(center, center, radius * (t / REVEAL_END).coerceAtMost(1f), fillPaint)
        // The old content shrinks and fades out, the new grows and fades in; the ✕ turns in from -90° and out to 90°.
        // The idle wave doesn't grow: it draws itself in (started in the status setter).
        // from != to while t < 1: one drawable drawn twice per frame would use its last alpha for both.
        if (t < 1f) drawContent(canvas, from, 1 - t, 1 - 0.4f * t, 90 * t)
        drawContent(canvas, to, t, if (to == BubbleStatus.IDLE) 1f else 0.6f + 0.4f * t, -90 * (1 - t))
    }

    private fun drawContent(canvas: Canvas, status: BubbleStatus, alpha: Float, scale: Float, degrees: Float) {
        val center = sizePx / 2f
        val alpha255 = (alpha * 255).roundToInt()
        canvas.withSave {
            scale(scale, scale, center, center)
            when (status) {
                BubbleStatus.WORKING -> {
                    val turn = spin.animatedValue as? Float ?: 0f
                    spinnerPaint.alpha = alpha255
                    drawArc(spinnerBounds, 360 * turn - 90, SPINNER_SWEEP, false, spinnerPaint)
                }
                BubbleStatus.LISTENING -> {
                    rotate(degrees, center, center)
                    close.alpha = alpha255
                    close.draw(this)
                }
                BubbleStatus.IDLE -> {
                    wavePaint.alpha = alpha255
                    if (waveDrawn >= 1f) {
                        drawPath(wave, wavePaint)
                    } else if (waveDrawn > 0f) {
                        waveSegment.rewind()
                        waveMeasure.getSegment(0f, waveMeasure.length * waveDrawn, waveSegment, true)
                        drawPath(waveSegment, wavePaint)
                    }
                }
                BubbleStatus.SUCCESS, BubbleStatus.FAILURE -> {
                    val icon = if (status == BubbleStatus.SUCCESS) check else exclamation
                    icon.alpha = alpha255
                    icon.draw(this)
                }
            }
        }
    }

    private fun fill(status: BubbleStatus) = when (status) {
        BubbleStatus.IDLE -> idleColor
        BubbleStatus.LISTENING, BubbleStatus.WORKING -> controlColor
        BubbleStatus.SUCCESS -> successColor
        BubbleStatus.FAILURE -> failureColor
    }

    private fun description(status: BubbleStatus) = when (status) {
        BubbleStatus.IDLE, BubbleStatus.SUCCESS, BubbleStatus.FAILURE -> R.string.bubble_start
        BubbleStatus.LISTENING -> R.string.bubble_cancel
        BubbleStatus.WORKING -> R.string.bubble_working
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimations()
    }

    override fun onDetachedFromWindow() {
        morph.end() // A re-attach draws the final state.
        spin.cancel()
        waveAnimator?.cancel()
        waveDrawn = 0f // A re-added bubble draws the wave in from nothing.
        super.onDetachedFromWindow()
    }

    private fun updateAnimations() {
        val working = status == BubbleStatus.WORKING ||
            (morph.isRunning && (from == BubbleStatus.WORKING || to == BubbleStatus.WORKING))
        if (working && isAttachedToWindow) {
            if (!spin.isStarted) spin.start()
        } else {
            spin.cancel()
        }
    }

    /** [id] as an [ICON_DP] square centred in the view; mutated, so alpha and tint stay local to this view. */
    private fun icon(id: Int) = context.getDrawable(id)!!.mutate().apply {
        val half = (ICON_DP * density / 2).roundToInt()
        setBounds(sizePx / 2 - half, sizePx / 2 - half, sizePx / 2 + half, sizePx / 2 + half)
    }

    private companion object {
        const val RADIUS_DP = 19
        const val MARGIN_DP = 6
        const val SHADOW_ROOM_DP = 6
        const val ICON_DP = 19
        const val SPINNER_STROKE_DP = 2.5f
        const val SPINNER_SWEEP = 270f
        const val MORPH_MS = 250L
        const val REVEAL_END = 0.8f

        /** The last path of iris_logo.xml (the waveform), in its path units. */
        const val WAVE_PATH = "M10.5,83.21c8.78,0,15.9-5.88,15.9-13.13v-26.27c0-7.25,7.12-13.13,15.9-13.13" +
            "s15.9,5.88,15.9,13.13v85.36c0,7.25,7.12,13.13,15.9,13.13s15.9-5.88,15.9-13.13V24.12" +
            "c0-7.25,7.12-13.13,15.9-13.13s15.9,5.88,15.9,13.13v85.36c0,7.25,7.12,13.13,15.9,13.13" +
            "s15.9-5.88,15.9-13.13v-26.27c0-7.25,7.12-13.13,15.9-13.13"

        /** The logo's stroke width, in path units. */
        const val WAVE_STROKE = 21f
        const val WAVE_WIDTH_DP = 21f
        const val WAVE_IN_MS = 500L

        /** Matches BubbleOverlay's STACK_OUT_MS: the window is removed at its end. */
        const val WAVE_OUT_MS = 200L
        val WAVE_EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
