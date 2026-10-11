package com.wynh.appapp

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/** Observe cancellation before ItemTouchHelper consumes the active drag gesture. */
class LibraryRecyclerView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null, style: Int = 0) : RecyclerView(context, attributes, style) {
    var onDragCancelled: (() -> Unit)? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) onDragCancelled?.invoke()
        return super.dispatchTouchEvent(event)
    }
}
