package com.wynh.appapp.bridge

import android.webkit.JavascriptInterface
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom

enum class ArgType { STRING, INT, BOOLEAN }

/** One JavaScript-callable method: its parameter types and the native call they are passed to. */
class BridgeMethod(vararg val params: ArgType, val invoke: (List<Any>) -> Any)

/**
 * The single raw object registered with `addJavascriptInterface`. Android injects it into every
 * frame, so it holds no capability of its own: each call must carry the per-WebView secret that
 * only the document-start shim of the subapp origin knows. Calls without it are rejected before
 * any argument is parsed or method is run.
 */
class TokenGatedBridge(
    private val token: String,
    private val methods: Map<String, BridgeMethod>,
    private val onLog: (String) -> Unit = {}
) {
    @Volatile private var rejectionLogged = false

    /** Returns `{"ok":true,"value":...}` or `{"ok":false,"error":"..."}`. */
    @JavascriptInterface
    fun call(token: String?, method: String?, argsJson: String?): String {
        if (!tokenMatches(this.token, token)) {
            if (!rejectionLogged) {
                rejectionLogged = true
                onLog("[Bridge Warning] Rejected a native bridge call from a frame without bridge access.")
            }
            return failure("Bridge access denied")
        }
        val target = method?.let(methods::get) ?: return failure("Method not found")
        val args = parseArgs(target.params, argsJson) ?: return failure("Invalid arguments for $method")
        return try {
            success(target.invoke(args))
        } catch (e: Exception) {
            onLog("[Bridge Error] $method failed: ${e.message}")
            failure("Java exception was raised during method invocation")
        }
    }

    companion object {
        /** Name of the raw interface; the shim deletes it from `window` before page scripts run. */
        const val INTERFACE_NAME = "__app2NativeBridge"
        val PUBLIC_NAMES = listOf("AndroidStorage", "AndroidMemory", "AndroidNotification")

        private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
        private val random = SecureRandom()

        /** 256 random bits, hex encoded. */
        fun newToken(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        fun tokenMatches(expected: String, supplied: String?): Boolean =
            supplied != null && MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())

        /** Converts a JSON argument array to [params], or null when the count or any type differs. */
        fun parseArgs(params: Array<out ArgType>, argsJson: String?): List<Any>? {
            val array = runCatching { JsonParser.parseString(argsJson ?: return null) as? JsonArray }.getOrNull()
                ?: return null
            if (array.size() != params.size) return null
            return params.mapIndexed { i, type -> coerce(type, array[i]) ?: return null }
        }

        /**
         * Strings also accept numbers and booleans (in their JSON text form); integers accept finite
         * numbers, truncated toward zero and saturated to the Int range like the former Java bridge.
         * Null, undefined, objects and arrays are rejected.
         */
        fun coerce(type: ArgType, value: JsonElement): Any? {
            val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
            return when (type) {
                ArgType.STRING -> primitive.asString
                ArgType.BOOLEAN -> if (primitive.isBoolean) primitive.asBoolean else null
                ArgType.INT -> if (!primitive.isNumber) null else primitive.asDouble.takeIf { it.isFinite() }?.let {
                    it.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
                }
            }
        }

        fun success(value: Any): String = gson.toJson(mapOf("ok" to true, "value" to value))
        fun failure(error: String): String = gson.toJson(mapOf("ok" to false, "error" to error))

        /**
         * Script injected at document start into documents of the subapp origin only. It keeps the
         * token and raw object in a closure, removes the raw object from `window`, and recreates
         * the three historical synchronous objects with the same method names, arity checks and
         * return values.
         */
        fun shimScript(token: String, methods: Map<String, BridgeMethod>): String {
            val arity = gson.toJson(methods.mapValues { it.value.params.size })
            return """(function(){'use strict';
var raw=window.$INTERFACE_NAME,token=${gson.toJson(token)},stringify=JSON.stringify,parse=JSON.parse,slice=Array.prototype.slice;
try{delete window.$INTERFACE_NAME;}catch(e){}
if(!raw||typeof raw.call!=='function')return;
var arity=$arity,api={};
Object.keys(arity).forEach(function(name){api[name]=function(){
if(arguments.length!==arity[name])throw new Error('Method not found');
var r=parse(raw.call(token,name,stringify(slice.call(arguments))));
if(!r.ok)throw new Error(r.error);
return r.value;};});
Object.freeze(api);
${gson.toJson(PUBLIC_NAMES)}.forEach(function(n){window[n]=api;});
})();"""
        }
    }
}
