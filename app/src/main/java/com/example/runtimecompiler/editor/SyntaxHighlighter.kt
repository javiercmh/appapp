package com.example.runtimecompiler.editor

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * Lightweight, regex-based syntax highlighter for Android EditText.
 * Provides color highlighting for HTML, CSS, JavaScript, and JSON without external dependencies.
 */
object SyntaxHighlighter {

    private val COLOR_KEYWORD = Color.parseColor("#FF7B72")    // Coral/Red
    private val COLOR_TAG = Color.parseColor("#7EE787")        // Light Green
    private val COLOR_ATTR = Color.parseColor("#79C0FF")       // Light Blue
    private val COLOR_STRING = Color.parseColor("#A5D6FF")     // Cyan/Sky
    private val COLOR_NUMBER = Color.parseColor("#FFA657")     // Orange
    private val COLOR_COMMENT = Color.parseColor("#8B949E")    // Slate Gray
    private val COLOR_PROPERTY = Color.parseColor("#D2A8FF")   // Purple

    // JavaScript regex patterns
    private val JS_KEYWORD_PATTERN = Pattern.compile(
        "\\b(const|let|var|function|return|if|else|for|while|do|switch|case|break|continue|new|this|class|extends|super|import|export|from|default|try|catch|finally|throw|async|await|typeof|instanceof|null|undefined|true|false|void|delete|in|of)\\b"
    )
    private val JS_STRING_PATTERN = Pattern.compile("(\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'|`[^`\\\\]*(?:\\\\.[^`\\\\]*)*`)")
    private val JS_NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b")
    private val JS_COMMENT_PATTERN = Pattern.compile("(//.*?$|/\\*.*?\\*/)", Pattern.MULTILINE or Pattern.DOTALL)

    // HTML regex patterns
    private val HTML_TAG_PATTERN = Pattern.compile("(</?[a-zA-Z0-9_-]+|/?>)")
    private val HTML_ATTR_PATTERN = Pattern.compile("\\s+([a-zA-Z0-9_:-]+)(?=\\s*=\\s*[\"'])")
    private val HTML_STRING_PATTERN = Pattern.compile("(\"[^\"]*\"|'[^']*')")
    private val HTML_COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL)

    // CSS regex patterns
    private val CSS_COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL)
    private val CSS_PROPERTY_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+)\\s*:")
    private val CSS_VALUE_PATTERN = Pattern.compile("(:\\s*)(#[a-fA-F0-9]{3,8}|\\d+(?:\\.\\d+)?(?:px|rem|em|%|vh|vw|ms|s)?|rgba?\\([^)]+\\)|var\\([^)]+\\))")
    private val CSS_SELECTOR_PATTERN = Pattern.compile("(^|\\})([^{/]+)(?=\\{)")

    // JSON regex patterns
    private val JSON_KEY_PATTERN = Pattern.compile("(\"[^\"]+\")\\s*:")
    private val JSON_STRING_PATTERN = Pattern.compile(":\\s*(\"[^\"]*\")")
    private val JSON_NUMBER_PATTERN = Pattern.compile(":\\s*(-?\\d+(?:\\.\\d+)?)")
    private val JSON_BOOL_PATTERN = Pattern.compile("\\b(true|false|null)\\b")

    /**
     * Applies syntax highlighting to an Editable Spannable based on file name extension.
     */
    fun highlight(editable: Editable, fileName: String) {
        val lower = fileName.lowercase()

        // 1. Remove existing syntax spans
        val existingSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        for (span in existingSpans) {
            editable.removeSpan(span)
        }

        val text = editable.toString()
        if (text.isBlank()) return

        when {
            lower.endsWith(".js") || lower.endsWith(".mjs") -> highlightJavaScript(editable, text)
            lower.endsWith(".html") || lower.endsWith(".htm") -> highlightHtml(editable, text)
            lower.endsWith(".css") -> highlightCss(editable, text)
            lower.endsWith(".json") -> highlightJson(editable, text)
        }
    }

    private fun highlightJavaScript(editable: Editable, text: String) {
        applyPattern(editable, text, JS_NUMBER_PATTERN, COLOR_NUMBER)
        applyPattern(editable, text, JS_KEYWORD_PATTERN, COLOR_KEYWORD)
        applyPattern(editable, text, JS_STRING_PATTERN, COLOR_STRING)
        applyPattern(editable, text, JS_COMMENT_PATTERN, COLOR_COMMENT)
    }

    private fun highlightHtml(editable: Editable, text: String) {
        applyPattern(editable, text, HTML_TAG_PATTERN, COLOR_TAG)
        applyPattern(editable, text, HTML_ATTR_PATTERN, COLOR_ATTR, group = 1)
        applyPattern(editable, text, HTML_STRING_PATTERN, COLOR_STRING)
        applyPattern(editable, text, HTML_COMMENT_PATTERN, COLOR_COMMENT)
    }

    private fun highlightCss(editable: Editable, text: String) {
        applyPattern(editable, text, CSS_SELECTOR_PATTERN, COLOR_TAG, group = 2)
        applyPattern(editable, text, CSS_PROPERTY_PATTERN, COLOR_PROPERTY, group = 1)
        applyPattern(editable, text, CSS_VALUE_PATTERN, COLOR_NUMBER, group = 2)
        applyPattern(editable, text, CSS_COMMENT_PATTERN, COLOR_COMMENT)
    }

    private fun highlightJson(editable: Editable, text: String) {
        applyPattern(editable, text, JSON_KEY_PATTERN, COLOR_ATTR, group = 1)
        applyPattern(editable, text, JSON_STRING_PATTERN, COLOR_STRING, group = 1)
        applyPattern(editable, text, JSON_NUMBER_PATTERN, COLOR_NUMBER, group = 1)
        applyPattern(editable, text, JSON_BOOL_PATTERN, COLOR_KEYWORD)
    }

    private fun applyPattern(
        editable: Editable,
        text: String,
        pattern: Pattern,
        color: Int,
        group: Int = 0
    ) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val start = matcher.start(group)
            val end = matcher.end(group)
            if (start in 0..editable.length && end in 0..editable.length && start < end) {
                editable.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
