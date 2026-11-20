package com.wynh.appapp.workspace

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View.MeasureSpec
import androidx.core.widget.NestedScrollView
import kotlin.math.roundToInt

/** Keeps backup actions outside the scrolling version list. */
class VersionHistoryScrollView(context: Context, attrs: AttributeSet?) : NestedScrollView(context, attrs) {
    private var draggingScrollbar = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = minOf((resources.displayMetrics.density * 184).roundToInt(), resources.displayMetrics.heightPixels / 3)
        val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight
            else minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        if (event.actionMasked == MotionEvent.ACTION_DOWN && overScrollbar(event.x)) true
        else super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && overScrollbar(event.x)) {
            draggingScrollbar = true
            parent.requestDisallowInterceptTouchEvent(true)
        }
        if (!draggingScrollbar) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val contentHeight = getChildAt(0)?.height ?: 0
                val viewportHeight = height - paddingTop - paddingBottom
                if (viewportHeight > 0 && contentHeight > viewportHeight) {
                    val thumb = viewportHeight.toFloat() * viewportHeight / contentHeight
                    val fraction = ((event.y - paddingTop - thumb / 2) / (viewportHeight - thumb)).coerceIn(0f, 1f)
                    scrollTo(0, ((contentHeight - viewportHeight) * fraction).roundToInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingScrollbar = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun overScrollbar(x: Float): Boolean {
        val edge = (12 * resources.displayMetrics.density).roundToInt()
        val nearEdge = if (layoutDirection == LAYOUT_DIRECTION_RTL) x <= edge else x >= width - edge
        return nearEdge && (canScrollVertically(-1) || canScrollVertically(1))
    }
}
