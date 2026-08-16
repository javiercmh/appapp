package com.example.runtimecompiler.bridge

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * JavaScript Interface exposed inside WebView as `window.AndroidMemory`.
 * Allows JavaScript runtime code to interact directly with off-heap direct native memory (ByteBuffers),
 * inspect physical system RAM, and perform high-performance byte operations.
 */
class NativeMemoryBridge(
    private val context: Context,
    private val onLogListener: ((String) -> Unit)? = null
) {
    private val systemMemoryManager = SystemMemoryManager(context)
    private val memoryBlocks = ConcurrentHashMap<String, MemoryBlock>()

    private fun log(message: String) {
        onLogListener?.invoke(message)
    }

    @JavascriptInterface
    fun allocate(sizeInBytes: Int): String {
        val safeSize = sizeInBytes.coerceIn(1, 64 * 1024 * 1024) // Cap between 1 byte and 64 MB
        val blockId = "mem_" + UUID.randomUUID().toString().substring(0, 8)
        val block = MemoryBlock(blockId, safeSize)
        memoryBlocks[blockId] = block
        log("[NativeMemory] Allocated block '$blockId' with $safeSize bytes off-heap")
        return blockId
    }

    @JavascriptInterface
    fun free(blockId: String): Boolean {
        val removed = memoryBlocks.remove(blockId)
        if (removed != null) {
            log("[NativeMemory] Freed block '$blockId' (${removed.size} bytes)")
            return true
        }
        return false
    }

    @JavascriptInterface
    fun clear(blockId: String): Boolean {
        val block = memoryBlocks[blockId] ?: return false
        block.clear()
        log("[NativeMemory] Cleared block '$blockId'")
        return true
    }

    @JavascriptInterface
    fun getBlockSize(blockId: String): Int {
        return memoryBlocks[blockId]?.size ?: -1
    }

    @JavascriptInterface
    fun writeString(blockId: String, offset: Int, text: String): Int {
        val block = memoryBlocks[blockId] ?: return -1
        val written = block.writeString(offset, text)
        log("[NativeMemory] Wrote $written bytes string to '$blockId'@+$offset")
        return written
    }

    @JavascriptInterface
    fun readString(blockId: String, offset: Int, length: Int): String {
        val block = memoryBlocks[blockId] ?: return ""
        return block.readString(offset, length)
    }

    @JavascriptInterface
    fun writeJson(blockId: String, jsonString: String): Boolean {
        val block = memoryBlocks[blockId] ?: return false
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        if (bytes.size + 4 > block.size) return false
        // Write 4-byte length header followed by UTF-8 bytes
        block.writeInt(0, bytes.size)
        block.writeBytes(4, bytes)
        log("[NativeMemory] Saved JSON payload (${bytes.size} bytes) in '$blockId'")
        return true
    }

    @JavascriptInterface
    fun readJson(blockId: String): String {
        val block = memoryBlocks[blockId] ?: return ""
        val length = block.readInt(0) ?: 0
        if (length <= 0 || length > block.size - 4) return ""
        return block.readString(4, length)
    }

    @JavascriptInterface
    fun writeBytesBase64(blockId: String, offset: Int, base64Data: String): Int {
        val block = memoryBlocks[blockId] ?: return -1
        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            block.writeBytes(offset, bytes)
        } catch (e: Exception) {
            -1
        }
    }

    @JavascriptInterface
    fun readBytesBase64(blockId: String, offset: Int, length: Int): String {
        val block = memoryBlocks[blockId] ?: return ""
        val bytes = block.readBytes(offset, length)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun writeByte(blockId: String, offset: Int, value: Int): Boolean {
        val block = memoryBlocks[blockId] ?: return false
        return block.writeByte(offset, value.toByte())
    }

    @JavascriptInterface
    fun readByte(blockId: String, offset: Int): Int {
        val block = memoryBlocks[blockId] ?: return -1
        return (block.readByte(offset)?.toInt() ?: -1) and 0xFF
    }

    @JavascriptInterface
    fun writeInt(blockId: String, offset: Int, value: Int): Boolean {
        val block = memoryBlocks[blockId] ?: return false
        return block.writeInt(offset, value)
    }

    @JavascriptInterface
    fun readInt(blockId: String, offset: Int): Int {
        val block = memoryBlocks[blockId] ?: return 0
        return block.readInt(offset) ?: 0
    }

    @JavascriptInterface
    fun getMemoryStats(): String {
        val statsObj = JSONObject(systemMemoryManager.getMemoryStatsJson())

        // Calculate total allocated off-heap memory
        var totalAllocatedOffHeap = 0L
        val blocksArray = JSONArray()
        memoryBlocks.forEach { (id, block) ->
            totalAllocatedOffHeap += block.size
            blocksArray.put(JSONObject().apply {
                put("id", id)
                put("size", block.size)
                put("createdAt", block.createdAt)
                put("lastModifiedAt", block.lastModifiedAt)
            })
        }

        statsObj.put("offHeapBlocksCount", memoryBlocks.size)
        statsObj.put("offHeapAllocatedBytes", totalAllocatedOffHeap)
        statsObj.put("blocks", blocksArray)

        return statsObj.toString()
    }

    @JavascriptInterface
    fun getActiveBlocks(): String {
        val array = JSONArray()
        memoryBlocks.forEach { (id, block) ->
            array.put(JSONObject().apply {
                put("id", id)
                put("size", block.size)
                put("lastModifiedAt", block.lastModifiedAt)
            })
        }
        return array.toString()
    }

    fun releaseAll() {
        memoryBlocks.clear()
    }
}
