package x.vladgba.fileman

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max

/** EditText with a line-number gutter. Scrolls itself in both directions; the gutter stays pinned on the left. */
class CodeEditText(ctx: Context) : EditText(ctx) {
    var onScroll: (() -> Unit)? = null

    var lineNumbers = true
        set(v) { field = v; digits = -1; requestLayout(); invalidate() }

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.mono()
        textAlign = Paint.Align.RIGHT
    }
    private val gutterPaint = Paint()
    private val invPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.mono() }
    private val one = CharArray(1)

    /** Draw markers for spaces, tabs, line ends and control characters. */
    var showInvisibles = false
        set(v) { field = v; invalidate() }
    private val gap = Ui.dp(ctx, 8)
    private val basePad = Ui.dp(ctx, 4)
    private var gutterW = 0
    private var digits = -1

    // Newline count before cacheOff; lets the gutter number lines without rescanning the whole text each frame.
    private var cacheOff = 0
    private var cacheLines = 0

    init {
        typeface = Fonts.mono()
        gravity = Gravity.TOP or Gravity.START
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        setHorizontallyScrolling(true) // must come after inputType, which resets it
        val night = Ui.isNight(ctx)
        numPaint.color = if (night) 0xFF6B7378.toInt() else 0xFF9AA0A6.toInt()
        invPaint.color = if (night) 0x80A0A8AE.toInt() else 0x80808890.toInt()
        gutterPaint.color = if (night) 0xFF1B2124.toInt() else 0xFFF1F3F4.toInt()
        numPaint.textSize = textSize * 0.85f
        setPadding(basePad, basePad, basePad, basePad)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (start < cacheOff) { cacheOff = 0; cacheLines = 0 }
            }
            override fun afterTextChanged(s: Editable) {}
        })
    }

    fun setWrap(wrap: Boolean) {
        hScroll = !wrap
        setHorizontallyScrolling(!wrap)
    }

    fun setFontSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        numPaint.textSize = textSize * 0.85f
        digits = -1
        requestLayout()
    }

    private fun newlinesBefore(off: Int): Int {
        val t = text
        var n = cacheLines
        if (off >= cacheOff) {
            for (i in cacheOff until off) if (t[i] == '\n') n++
        } else {
            for (i in off until cacheOff) if (t[i] == '\n') n--
        }
        cacheOff = off
        cacheLines = n
        return n
    }

    /** Resize the gutter when the number of digits changes. Returns true if a relayout was requested. */
    private fun syncGutter(): Boolean {
        val want = if (!lineNumbers) 0 else max(2, (layout?.lineCount ?: 1).toString().length)
        if (want == digits) return false
        digits = want
        gutterW = if (want == 0) 0 else (numPaint.measureText("0") * want).toInt() + gap * 2
        setPadding(basePad + gutterW, paddingTop, paddingRight, paddingBottom)
        return true
    }

    // ---------------------------------------------------------------- kinetic scrolling
    //
    // EditText scrolls by following the finger and has no fling. Like ScrollView, we let the EditText see the
    // touch until it is clearly a drag; then we cancel the gesture for EditText, scroll ourselves (locked to
    // one axis) and fling with an OverScroller on release. Taps, cursor placement, long-press selection and
    // selection handles are untouched.

    private val flinger = OverScroller(ctx)
    private var tracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity
    private var hScroll = true
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var selectionAtDown = false
    private var dragAxis = 0 // 0 = not dragging (EditText owns the touch), 1 = vertical, 2 = horizontal
    private var swallow = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            swallow = !flinger.isFinished // touching while coasting just stops it (no cursor jump)
            flinger.forceFinished(true)
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
            downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
            dragAxis = 0
            selectionAtDown = hasSelection()
        }
        tracker?.addMovement(e)
        val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL

        if (swallow) {
            if (end) finishTouch()
            return true
        }

        if (dragAxis == 0 && action == MotionEvent.ACTION_MOVE) {
            val dx = abs(e.x - downX)
            val dy = abs(e.y - downY)
            // A selection that appeared during this gesture means long-press drag-select: leave it to EditText.
            val selecting = !selectionAtDown && hasSelection()
            if (!selecting && (dx > touchSlop || dy > touchSlop)) {
                dragAxis = if (hScroll && dx > dy) 2 else 1
                lastX = e.x; lastY = e.y
                val cancel = MotionEvent.obtain(e).apply { setAction(MotionEvent.ACTION_CANCEL) }
                super.onTouchEvent(cancel)
                cancel.recycle()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        if (dragAxis != 0) {
            when (action) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragAxis == 1) scrollClamped(0, (lastY - e.y).toInt())
                    else scrollClamped((lastX - e.x).toInt(), 0)
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.let {
                        it.computeCurrentVelocity(1000, maxFling.toFloat())
                        val v = if (dragAxis == 1) it.yVelocity else it.xVelocity
                        if (abs(v) > minFling) fling(-v.toInt())
                    }
                    finishTouch()
                }
                MotionEvent.ACTION_CANCEL -> finishTouch()
            }
            return true
        }

        val handled = super.onTouchEvent(e)
        if (end) finishTouch()
        return handled
    }

    private fun finishTouch() {
        swallow = false
        tracker?.recycle()
        tracker = null
    }

    private fun maxScrollY(): Int {
        val l = layout ?: return 0
        return max(0, l.height + totalPaddingTop + totalPaddingBottom - height)
    }

    /** Widest visible line bounds horizontal scrolling (layout width is "very wide" when not wrapping). */
    private fun maxScrollX(): Int {
        val l = layout ?: return 0
        if (!hScroll) return 0
        val first = l.getLineForVertical(max(0, scrollY - totalPaddingTop))
        val last = l.getLineForVertical(scrollY - totalPaddingTop + height)
        var w = 0f
        for (i in first..last) w = max(w, l.getLineWidth(i))
        return max(scrollX, max(0, (w + totalPaddingLeft + totalPaddingRight - width).toInt()))
    }

    private fun scrollClamped(dx: Int, dy: Int) {
        val x = (scrollX + dx).coerceIn(0, maxScrollX())
        val y = (scrollY + dy).coerceIn(0, maxScrollY())
        if (x != scrollX || y != scrollY) scrollTo(x, y)
    }

    private fun fling(velocity: Int) {
        if (dragAxis == 1) flinger.fling(scrollX, scrollY, 0, velocity, scrollX, scrollX, 0, maxScrollY())
        else flinger.fling(scrollX, scrollY, velocity, 0, 0, maxScrollX(), scrollY, scrollY)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (flinger.computeScrollOffset()) {
            scrollTo(flinger.currX, flinger.currY)
            postInvalidateOnAnimation()
        } else {
            super.computeScroll()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke()
    }

    override fun onDraw(c: Canvas) {
        if (syncGutter()) { invalidate(); return }
        super.onDraw(c)
        val l = layout ?: return
        val t = text
        if (t.isEmpty()) return
        val padTop = totalPaddingTop
        val first = l.getLineForVertical(max(0, scrollY - padTop))
        val last = l.getLineForVertical(scrollY - padTop + height)
        if (showInvisibles) drawInvisibles(c, l, first, last)
        if (!lineNumbers) return
        val left = scrollX.toFloat()
        c.drawRect(left, scrollY.toFloat(), left + gutterW, (scrollY + height).toFloat(), gutterPaint)
        val x = left + gutterW - gap
        var nl = newlinesBefore(l.getLineStart(first))
        for (i in first..last) {
            val s = l.getLineStart(i)
            val logicalStart = i == 0 || (s > 0 && t[s - 1] == '\n')
            if (i > first && logicalStart) nl++
            if (logicalStart) c.drawText((nl + 1).toString(), x, (l.getLineBaseline(i) + padTop).toFloat(), numPaint)
        }
    }

    /** Symbol for an invisible character, or 0 if the character is visible. */
    private fun symbol(ch: Char): Char = when {
        ch == ' ' -> '\u00B7'                     // ·
        ch == '\t' -> '\u2192'                    // →
        ch == '\n' -> '\u00B6'                    // ¶
        ch == '\u00A0' -> '\u00B0'                // ° non-breaking space
        ch < ' ' -> (0x2400 + ch.code).toChar() // ␀ ␁ … ␍ … control pictures
        ch == '\u007F' -> '\u2421'                // ␡
        ch == '\uFEFF' || ch == '\u200B' -> '\u2423' // ␣ zero-width / BOM
        else -> 0.toChar()
    }

    private fun drawInvisibles(c: Canvas, l: android.text.Layout, first: Int, last: Int) {
        val t = text
        val left = totalPaddingLeft.toFloat()
        val top = totalPaddingTop
        invPaint.textSize = paint.textSize
        for (i in first..last) {
            val s = l.getLineStart(i)
            val e = l.getLineEnd(i)
            if (e - s > 4000) continue // very long line: skip, getPrimaryHorizontal is O(line)
            val y = (l.getLineBaseline(i) + top).toFloat()
            for (k in s until e) {
                val sym = symbol(t[k])
                if (sym.code == 0) continue
                one[0] = sym
                c.drawText(one, 0, 1, left + l.getPrimaryHorizontal(k), y, invPaint)
            }
        }
    }
}
