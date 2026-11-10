package io.github.phoenix153.mytube

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Hülle um den Stöber-Tab, die auf der Videoseite das Wischen nach unten über dem Video
 * erkennt (wie in der YouTube-App: Video verkleinern).
 *
 * Gilt nur, wenn [enabled] true liefert (Videoseite, Hochformat, kein Vollbild), und nur im
 * oberen Bereich, wo das Video sitzt (16:9 über die volle Breite). Antippen, Doppeltippen
 * und seitliches Wischen bleiben beim YouTube-Player; erst ein deutlicher, überwiegend
 * senkrechter Zug nach unten gehört uns.
 */
@SuppressLint("ViewConstructor")
class SwipeDownLayout(
    context: Context,
    private val enabled: () -> Boolean,
    private val onSwipedDown: () -> Unit,
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val triggerDistance = 80 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                tracking = height > width && ev.y < width * 9f / 16f && enabled()
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dy = ev.y - downY
                if (dy > touchSlop * 2 && dy > abs(ev.x - downX) * 2) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                dragging = false
                tracking = false
                if (ev.y - downY > triggerDistance) onSwipedDown()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracking = false
            }
        }
        return true
    }
}
