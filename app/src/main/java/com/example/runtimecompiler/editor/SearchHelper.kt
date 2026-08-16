package com.example.runtimecompiler.editor

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.widget.EditText
import android.widget.ScrollView
import java.util.Locale

/**
 * Helper to perform in-file search and match highlighting inside an EditText,
 * with automatic viewport scrolling to matched terms.
 */
class SearchHelper(
    private val editText: EditText,
    private val scrollView: ScrollView? = null,
    private val onMatchUpdated: (current: Int, total: Int) -> Unit
) {
    private val matches = mutableListOf<Pair<Int, Int>>()
    private var currentIndex = -1
    private var lastQuery = ""

    private val matchBgColor = Color.parseColor("#4DFFD600")       // Translucent Yellow for all matches
    private val activeMatchBgColor = Color.parseColor("#99FF9800") // Brighter Amber for active match

    fun search(query: String) {
        lastQuery = query
        clearHighlights()
        matches.clear()
        currentIndex = -1

        if (query.isBlank()) {
            onMatchUpdated(0, 0)
            return
        }

        val text = editText.text?.toString() ?: ""
        val lowerText = text.lowercase(Locale.getDefault())
        val lowerQuery = query.lowercase(Locale.getDefault())

        var startIndex = 0
        while (startIndex < lowerText.length) {
            val foundIndex = lowerText.indexOf(lowerQuery, startIndex)
            if (foundIndex == -1) break
            val endIndex = foundIndex + lowerQuery.length
            matches.add(Pair(foundIndex, endIndex))
            startIndex = endIndex
        }

        if (matches.isNotEmpty()) {
            currentIndex = 0
            highlightMatches()
            scrollToCurrentMatch()
        }

        onMatchUpdated(if (matches.isEmpty()) 0 else currentIndex + 1, matches.size)
    }

    fun nextMatch() {
        if (matches.isEmpty()) return
        currentIndex = (currentIndex + 1) % matches.size
        highlightMatches()
        scrollToCurrentMatch()
        onMatchUpdated(currentIndex + 1, matches.size)
    }

    fun prevMatch() {
        if (matches.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) matches.size - 1 else currentIndex - 1
        highlightMatches()
        scrollToCurrentMatch()
        onMatchUpdated(currentIndex + 1, matches.size)
    }

    fun clear() {
        clearHighlights()
        matches.clear()
        currentIndex = -1
        lastQuery = ""
        onMatchUpdated(0, 0)
    }

    private fun highlightMatches() {
        val editable: Editable = editText.text ?: return
        clearHighlights()

        for (i in matches.indices) {
            val match = matches[i]
            val color = if (i == currentIndex) activeMatchBgColor else matchBgColor
            if (match.first in 0..editable.length && match.second in 0..editable.length) {
                editable.setSpan(
                    BackgroundColorSpan(color),
                    match.first,
                    match.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun scrollToCurrentMatch() {
        if (currentIndex !in matches.indices) return
        val match = matches[currentIndex]
        editText.setSelection(match.first, match.second)

        // Smoothly scroll the parent ScrollView to bring the matched line into view
        val layout = editText.layout
        if (layout != null) {
            val line = layout.getLineForOffset(match.first)
            val lineTop = layout.getLineTop(line)
            scrollView?.smoothScrollTo(0, maxOf(0, lineTop - 120))
        }
    }

    private fun clearHighlights() {
        val editable: Editable = editText.text ?: return
        val spans = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        for (span in spans) {
            editable.removeSpan(span)
        }
    }
}
