package com.wynh.appapp.ai.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader

/**
 * Reads a Responses event stream and returns its text once `response.completed` arrives.
 *
 * Only the answer is bounded ([maxOutputUnits] UTF-16 units), not the stream: reasoning, metadata
 * and the events that repeat the whole text can be many times its size. When an `event:` field
 * names an event this parser does not use, its data is skipped as it is read, never held.
 */
object SseTextParser {
    private const val DELTA = "response.output_text.delta"
    private const val COMPLETED = "response.completed"
    /** Events whose data is parsed. Everything else that names itself is skipped unread. */
    private val PARSED = setOf(DELTA, "response.failed", "error", "response.incomplete")
    private const val MAX_FIELD = 16
    private const val MAX_EVENT_NAME = 256L

    fun read(reader: Reader, maxOutputUnits: Int = HttpGateway.MIN_OUTPUT_UNITS): String {
        // An unnamed event is held to read its type; twice the answer bound leaves room for JSON escaping.
        val maxEventUnits = maxOutputUnits.toLong() * 2
        val text = StringBuilder()
        val data = StringBuilder()
        var hasData = false
        var eventName: String? = null
        var completed = false

        /** Ends the current event; true once the stream is complete. */
        fun dispatch(): Boolean {
            val name = eventName
            eventName = null
            if (!hasData) { data.setLength(0); return false }
            hasData = false
            if (name == COMPLETED) { data.setLength(0); completed = true; return true }
            if (name != null && name !in PARSED) { data.setLength(0); return false }
            val value = try { JsonParser.parseString(data.toString()).asJsonObject } catch (_: Exception) {
                throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "An inference stream event was not valid JSON. Please try again.")
            }
            data.setLength(0)
            when (value.get("type")?.asString) {
                DELTA -> {
                    val delta = value.get("delta")?.takeIf { it.isJsonPrimitive }?.asString ?: throw HttpGateway.malformed()
                    if (text.length.toLong() + delta.length > maxOutputUnits) throw HttpGateway.tooLarge(maxOutputUnits)
                    text.append(delta)
                }
                COMPLETED -> { completed = true; return true }
                "response.failed", "error" -> {
                    val error = value.getAsJsonObject("response")?.getAsJsonObject("error") ?: value.getAsJsonObject("error") ?: value
                    throw HttpGateway.errorFor(400, error.get("code")?.asString)
                }
                "response.incomplete" -> throw HttpGateway.incomplete(reason(value))
            }
            return false
        }

        while (true) {
            // The field name runs to a colon or the end of the line. Unknown and overlong fields are skipped.
            val field = StringBuilder()
            var end: Int
            while (true) {
                end = reader.read()
                if (end == -1 || end == '\n'.code || end == ':'.code) break
                if (end == '\r'.code) continue
                if (field.length == MAX_FIELD) { field.setLength(0); field.append('\u0000'); end = skipLine(reader); break }
                field.append(end.toChar())
            }
            if (field.isEmpty() && end != ':'.code) {
                // A blank line, or the end of the stream, ends the event.
                if (dispatch() || end == -1) break
                continue
            }
            val hasValue = end == ':'.code
            when (field.toString()) {
                "data" -> {
                    if (eventName != null && eventName !in PARSED) {
                        if (hasValue) end = skipLine(reader)
                    } else {
                        if (hasData) data.append('\n')
                        if (hasValue) end = readValue(reader, data, maxEventUnits) { throw HttpGateway.tooLarge(maxOutputUnits) }
                    }
                    hasData = true
                }
                "event" -> {
                    val name = StringBuilder()
                    // An overlong name is no event this parser uses: it stays non-null, so its data is skipped.
                    if (hasValue) end = readValue(reader, name, MAX_EVENT_NAME) { name.setLength(0); name.append('\u0000'); skipLine(reader) }
                    eventName = name.toString()
                }
                // Comments (an empty field), id and retry.
                else -> if (hasValue) end = skipLine(reader)
            }
            if (end == -1) { dispatch(); break }
        }
        if (!completed) throw ApiFailure(ApiFailure.Kind.NETWORK, "The response stream ended before completion. Please try again.")
        if (text.isBlank()) throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE,
            "The inference completed without text output. Please try another available model.")
        return text.toString()
    }

    private fun reason(value: JsonObject): String? = runCatching {
        (value.getAsJsonObject("response") ?: value).getAsJsonObject("incomplete_details")?.get("reason")?.asString
    }.getOrNull()

    /**
     * Appends the rest of the line after one optional leading space and returns what ended it (-1 or
     * a newline). When [into] would pass [max] units, returns [overflow], which may throw instead.
     */
    private inline fun readValue(reader: Reader, into: StringBuilder, max: Long, overflow: () -> Int): Int {
        var next = reader.read()
        if (next == ' '.code) next = reader.read()
        while (next != -1 && next != '\n'.code) {
            if (next != '\r'.code) {
                if (into.length >= max) return overflow()
                into.append(next.toChar())
            }
            next = reader.read()
        }
        return next
    }

    private fun skipLine(reader: Reader): Int {
        var next = reader.read()
        while (next != -1 && next != '\n'.code) next = reader.read()
        return next
    }
}
