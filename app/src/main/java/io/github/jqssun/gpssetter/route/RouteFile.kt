package io.github.jqssun.gpssetter.route

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_ROUTE_FILE_MIB = 2
internal const val MAX_ROUTE_FILE_BYTES = MAX_ROUTE_FILE_MIB * 1024 * 1024

internal class RouteFileTooLargeException : IOException()

// File-picker providers do not always supply a size. Enforce the limit on the stream itself.
internal fun InputStream.readRouteText(): String {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream(buffer.size)
    while (true) {
        val remaining = MAX_ROUTE_FILE_BYTES - output.size()
        val count = read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (count < 0) return output.toString(Charsets.UTF_8.name())
        if (count > remaining) throw RouteFileTooLargeException()
        output.write(buffer, 0, count)
    }
}
