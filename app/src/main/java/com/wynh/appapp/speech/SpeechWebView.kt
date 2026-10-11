package com.wynh.appapp.speech

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView

class SpeechWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {
    private var selectionGeneration = 0
    fun invalidateSelection() { selectionGeneration++ }
    var speakSelection: ((String) -> Unit)? = null
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    private fun wrap(original: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = original.onCreateActionMode(mode, menu)
            if (created && menu.findItem(SPEAK) == null)
                menu.add(Menu.NONE, SPEAK, Menu.NONE, "Speak").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return created
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = original.onPrepareActionMode(mode, menu)
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != SPEAK) return original.onActionItemClicked(mode, item)
            // Read the selection before closing Android's action mode.
            val generation = selectionGeneration
            evaluateJavascript(SELECTION) { result ->
                if (generation == selectionGeneration) { speakSelection?.invoke(result); mode.finish() }
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)
        override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }
    companion object {
        private const val SPEAK = 0x41505053
        // Walk selected text nodes so selections spanning differently annotated languages are rejected.
        private val SELECTION = """
            (function(){
              function lang(n){var e=n.nodeType===1?n:n.parentElement;while(e){if(e.hasAttribute('lang'))return e.getAttribute('lang');e=e.parentElement;}return '';}
              var e=document.activeElement;
              if(e && (e.tagName==='TEXTAREA'||e.tagName==='INPUT')){
                if(e.type==='password')return {text:'',language:''};
                return {text:e.value.slice(e.selectionStart||0,e.selectionEnd||0),language:lang(e)};
              }
              var s=window.getSelection();if(!s||s.rangeCount!==1)return {text:'',language:''};
              var r=s.getRangeAt(0),root=r.commonAncestorContainer,languages=new Set();
              if(root.nodeType===3)languages.add(lang(root));
              else {var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),n;
                while(n=w.nextNode())if(n.textContent.trim()&&r.intersectsNode(n)){
                  var a=n===r.startContainer?r.startOffset:0,b=n===r.endContainer?r.endOffset:n.length;
                  if(n.textContent.slice(a,b).trim())languages.add(lang(n));
                }}
              return {text:s.toString(),language:languages.size===1?Array.from(languages)[0]:''};
            })()
        """.trimIndent()
    }
}
