package com.wynh.appapp.ai.network

import com.google.gson.JsonParser
import java.io.BufferedReader

object SseTextParser {
    fun read(reader: BufferedReader): String {
        val text = StringBuilder()
        val event = StringBuilder()
        var total = 0
        var completed = false
        fun consume(): Boolean {
            if (event.isEmpty()) return false
            val value = try { JsonParser.parseString(event.toString()).asJsonObject } catch (_: Exception) {
                throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "An inference stream event was not valid JSON. Please try again.")
            }
            event.clear()
            when (value.get("type")?.asString) {
                "response.output_text.delta" -> text.append(value.get("delta")?.asString ?: throw HttpGateway.malformed())
                "response.completed" -> { completed = true; return true }
                "response.failed", "error" -> {
                    val error = value.getAsJsonObject("response")?.getAsJsonObject("error") ?: value.getAsJsonObject("error") ?: value
                    throw HttpGateway.errorFor(400, error.get("code")?.asString)
                }
                "response.incomplete" -> throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE, "The response was incomplete. Please try again.")
            }
            return false
        }
        while (true) {
            // Bound individual lines as well as the total stream before allocating arbitrary input.
            val line = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next == -1 || next == '\n'.code) break
                if (++total > 2_097_152 || line.length >= 262_144) throw HttpGateway.malformed()
                if (next != '\r'.code) line.append(next.toChar())
            }
            if (line.isEmpty()) {
                if (consume()) break
                if (reader.peekEnd()) break
            } else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.substring(5).removePrefix(" "))
            }
        }
        if (!completed) throw ApiFailure(ApiFailure.Kind.NETWORK, "The response stream ended before completion. Please try again.")
        if (text.isBlank()) throw ApiFailure(ApiFailure.Kind.INVALID_RESPONSE,
            "The inference completed without text output. Please try another available model.")
        return text.toString()
    }

    private fun BufferedReader.peekEnd(): Boolean {
        mark(1)
        val next = read()
        reset()
        return next == -1
    }
}
