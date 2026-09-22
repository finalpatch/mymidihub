package com.mymidihub

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A small patch bay: port cards on each side and cables between them.
 * Native dialogs provide the equivalent connection controls for accessibility. */
class RoutingView(context: Context) : View(context) {
    var onConnect: (String, String) -> Unit = { _, _ -> }
    var onPortMenu: (PortState) -> Unit = {}
    var snapshot = Snapshot()
        set(value) { field = value; arrangePorts(); requestLayout(); invalidate() }
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val boxes = linkedMapOf<String, RectF>()
    private val dashed = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    private val cablePath = Path()
    private var selected: String? = null
    private var dragging = false
    private var downPort: PortState? = null
    private var downX = 0f
    private var downY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var longPressed = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        if (!dragging) downPort?.let {
            longPressed = true; selected = null
            parent.requestDisallowInterceptTouchEvent(false)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onPortMenu(it); invalidate()
        }
    }

    init {
        isClickable = true
        contentDescription = "MIDI routing board. Drag a source to a destination. Use the Routes button for accessible connection controls."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = max(snapshot.ports.count { it.endpoint.source }, snapshot.ports.count { !it.endpoint.source })
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(dp(48f + max(2, rows) * 88f + 16f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val foreground = Color.parseColor(if (dark) "#E5ECE9" else "#172420")
        val muted = Color.parseColor(if (dark) "#ABB8B3" else "#56665F")
        val accent = Color.parseColor(if (dark) "#6FE1C5" else "#147D70")
        val card = Color.parseColor(if (dark) "#202B27" else "#FFFFFF")
        val margin = dp(12f)
        val channel = min(dp(180f), max(dp(64f), width * .20f))
        val cardWidth = (width - channel) / 2 - margin
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = dp(14f); text.color = foreground
        canvas.drawText("SOURCES", margin, dp(28f), text)
        canvas.drawText("DESTINATIONS", width - margin - cardWidth, dp(28f), text)

        snapshot.routes.forEach { route ->
            val a = boxes[route.source] ?: return@forEach
            val b = boxes[route.destination] ?: return@forEach
            paint.color = if (route in snapshot.active) accent else muted
            paint.alpha = if (route in snapshot.active) 220 else 100
            paint.pathEffect = if (route in snapshot.active) null else dashed
            cable(canvas, a.right, a.centerY(), b.left, b.centerY())
        }
        paint.pathEffect = null; paint.alpha = 255
        if (dragging) boxes[selected]?.let { a ->
            paint.color = accent; cable(canvas, a.right, a.centerY(), pointerX, pointerY)
        }
        snapshot.ports.forEach { port ->
            val box = boxes.getValue(port.endpoint.id)
            paint.style = Paint.Style.FILL; paint.color = card
            canvas.drawRoundRect(box, dp(6f), dp(6f), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(if (selected == port.endpoint.id) 2f else 1f)
            paint.color = if (selected == port.endpoint.id) accent else muted
            paint.alpha = if (selected == port.endpoint.id) 255 else 70
            canvas.drawRoundRect(box, dp(6f), dp(6f), paint)
            paint.style = Paint.Style.FILL; paint.alpha = 255
            val x = box.left + dp(10f)
            val availableWidth = box.width() - dp(20f)
            text.color = foreground; text.textSize = dp(14f)
            val lines = wrap(port.endpoint.name, availableWidth)
            canvas.drawText(lines.first, x, box.top + dp(22f), text)
            canvas.drawText(lines.second, x, box.top + dp(40f), text)
            text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            text.color = muted; text.textSize = dp(11f)
            val status = TextUtils.ellipsize(port.status, text, availableWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(status, x, box.bottom - dp(10f), text)
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.color = if (port.available) accent else muted
            canvas.drawCircle(if (port.endpoint.source) box.right else box.left, box.centerY(), dp(5f), paint)
        }
        if (snapshot.ports.isEmpty()) {
            text.color = muted; text.textSize = dp(14f)
            canvas.drawText("Use Ports to add virtual ports.", margin, dp(90f), text)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { arrangePorts() }

    private fun arrangePorts() {
        val margin = dp(12f)
        val channel = min(dp(180f), max(dp(64f), width * .20f))
        val cardWidth = (width - channel) / 2 - margin
        boxes.clear()
        snapshot.ports.filter { it.endpoint.source }.forEachIndexed { index, port ->
            boxes[port.endpoint.id] = RectF(margin, dp(48f + index * 88f), margin + cardWidth, dp(124f + index * 88f))
        }
        snapshot.ports.filterNot { it.endpoint.source }.forEachIndexed { index, port ->
            boxes[port.endpoint.id] = RectF(width - margin - cardWidth, dp(48f + index * 88f), width - margin, dp(124f + index * 88f))
        }
    }

    private fun wrap(label: String, available: Float): Pair<String, String> {
        if (text.measureText(label) <= available) return label to ""
        val count = text.breakText(label, true, available, null)
        val split = label.lastIndexOf(' ', count).takeIf { it > count / 2 } ?: count
        return label.take(split) to TextUtils.ellipsize(label.drop(split).trim(), text, available, TextUtils.TruncateAt.END).toString()
    }

    private fun cable(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f)
        val curve = max(dp(24f), abs(x2 - x1) * .45f)
        cablePath.reset()
        cablePath.moveTo(x1, y1)
        cablePath.cubicTo(x1 + curve, y1, x2 - curve, y2, x2, y2)
        canvas.drawPath(cablePath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun hit(x: Float, y: Float): PortState? {
        val id = boxes.entries.firstOrNull { (_, rect) ->
            x >= rect.left - dp(12f) && x <= rect.right + dp(12f) && y >= rect.top && y <= rect.bottom
        }?.key
        return snapshot.ports.firstOrNull { it.endpoint.id == id }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPort = hit(event.x, event.y)
                downX = event.x; downY = event.y
                pointerX = event.x; pointerY = event.y
                longPressed = false; dragging = false
                // Let a vertical swipe scroll until a horizontal drag commits to routing.
                if (downPort != null) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) removeCallbacks(longPress)
                if (!dragging && !longPressed && downPort?.endpoint?.source == true && abs(dx) > touchSlop) {
                    selected = downPort?.endpoint?.id; dragging = true
                }
                if (!dragging && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                if (dragging) {
                    pointerX = event.x; pointerY = event.y
                    val scroll = parent as? ScrollView
                    scroll?.let {
                        if (event.y - it.scrollY > it.height - dp(60f)) it.scrollBy(0, dp(10f).toInt())
                        if (event.y - it.scrollY < dp(60f)) it.scrollBy(0, -dp(10f).toInt())
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                parent.requestDisallowInterceptTouchEvent(false)
                val target = hit(event.x, event.y)
                if (!longPressed) {
                    if (dragging) {
                        if (target?.endpoint?.source == false) selected?.let { onConnect(it, target.endpoint.id) }
                        selected = null
                    } else if (abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) {
                        if (target?.endpoint?.source == true) selected = target.endpoint.id
                        else if (target != null && selected != null) { onConnect(selected!!, target.endpoint.id); selected = null }
                        else selected = null
                        performClick()
                    }
                }
                dragging = false; downPort = null; invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress); dragging = false; downPort = null
                parent.requestDisallowInterceptTouchEvent(false); invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { removeCallbacks(longPress); super.onDetachedFromWindow() }
}
