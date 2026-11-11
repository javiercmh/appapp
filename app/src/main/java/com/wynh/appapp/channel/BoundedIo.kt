package com.wynh.appapp.channel

import java.io.InputStream
import java.io.OutputStream

/** Streaming copy that counts the bytes itself and never trusts a length hint. */
object BoundedIo {
    class LimitExceeded : java.io.IOException("Size limit exceeded")

    fun copy(input: InputStream, output: OutputStream, max: Long, buffer: ByteArray = ByteArray(64 * 1024)): Long {
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > max) throw LimitExceeded()
            output.write(buffer, 0, count)
        }
    }
}
