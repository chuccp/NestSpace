package com.coke.nestspace.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readBytesLimited(
    maxBytes: Int,
    onExceeded: () -> Nothing
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) onExceeded()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
