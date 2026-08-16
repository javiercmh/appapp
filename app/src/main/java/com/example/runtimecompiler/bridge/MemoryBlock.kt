package com.example.runtimecompiler.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Encapsulates an off-heap Direct ByteBuffer allocated in native memory.
 * Provides thread-safe, boundary-checked reads and writes for primitives, strings, and byte arrays.
 */
class MemoryBlock(
    val id: String,
    val size: Int
) {
    // Allocate direct off-heap native memory
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    val createdAt: Long = System.currentTimeMillis()
    var lastModifiedAt: Long = System.currentTimeMillis()
        private set

    @Synchronized
    fun writeByte(offset: Int, value: Byte): Boolean {
        if (offset < 0 || offset >= size) return false
        buffer.put(offset, value)
        lastModifiedAt = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun readByte(offset: Int): Byte? {
        if (offset < 0 || offset >= size) return null
        return buffer.get(offset)
    }

    @Synchronized
    fun writeInt(offset: Int, value: Int): Boolean {
        if (offset < 0 || offset + 4 > size) return false
        buffer.putInt(offset, value)
        lastModifiedAt = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun readInt(offset: Int): Int? {
        if (offset < 0 || offset + 4 > size) return null
        return buffer.getInt(offset)
    }

    @Synchronized
    fun writeBytes(offset: Int, src: ByteArray): Int {
        if (offset < 0 || offset >= size) return 0
        val bytesToWrite = minOf(src.size, size - offset)
        buffer.position(offset)
        buffer.put(src, 0, bytesToWrite)
        lastModifiedAt = System.currentTimeMillis()
        return bytesToWrite
    }

    @Synchronized
    fun readBytes(offset: Int, length: Int): ByteArray {
        if (offset < 0 || offset >= size) return ByteArray(0)
        val bytesToRead = minOf(length, size - offset)
        val dst = ByteArray(bytesToRead)
        buffer.position(offset)
        buffer.get(dst, 0, bytesToRead)
        return dst
    }

    @Synchronized
    fun writeString(offset: Int, text: String): Int {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return writeBytes(offset, bytes)
    }

    @Synchronized
    fun readString(offset: Int, length: Int): String {
        val bytes = readBytes(offset, length)
        return String(bytes, StandardCharsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        for (i in 0 until size) {
            buffer.put(i, 0.toByte())
        }
        lastModifiedAt = System.currentTimeMillis()
    }
}
