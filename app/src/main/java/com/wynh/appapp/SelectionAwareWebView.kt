package com.wynh.appapp

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/**
 * WebView that can hide Android's floating text-selection menu. An emptied floating menu draws no
 * toolbar, while the selection handles and the page's selection events keep working. Only menus that
 * carry the standard selection actions (copy, cut, paste, select all, share) are emptied; any other
 * action mode, such as one that only offers text-service or IME entries, is left untouched.
 */
class SelectionAwareWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var selectionMenuSuppressed = false

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let(::SuppressibleCallback), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let(::SuppressibleCallback))

    private inner class SuppressibleCallback(private val inner: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = inner.onCreateActionMode(mode, menu)
            if (selectionMenuSuppressed && hasSelectionActions(menu)) menu.clear()
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val changed = inner.onPrepareActionMode(mode, menu)
            if (selectionMenuSuppressed && hasSelectionActions(menu)) { menu.clear(); return true }
            return changed
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
            inner.onActionItemClicked(mode, item)

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            (inner as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect)
                ?: super.onGetContentRect(mode, view, outRect)
        }
    }

    private fun hasSelectionActions(menu: Menu): Boolean =
        (0 until menu.size()).any { menu.getItem(it).itemId in SELECTION_ACTION_IDS }

    private companion object {
        val SELECTION_ACTION_IDS = setOf(
            android.R.id.copy,
            android.R.id.cut,
            android.R.id.paste,
            android.R.id.pasteAsPlainText,
            android.R.id.selectAll,
            android.R.id.shareText
        )
    }
}
