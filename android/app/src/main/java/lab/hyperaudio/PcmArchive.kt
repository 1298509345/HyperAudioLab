package lab.hyperaudio

import java.io.Closeable
import java.io.OutputStream

/** Saves at most five minutes of 48 kHz PCM16 (mono by default); forwarding has its own lifetime. */
class PcmArchive(private val output: OutputStream, private val maxBytes: Long = 28_800_000L) : Closeable {
    private var bytesWritten = 0L
    private var closed = false

    init { require(maxBytes > 0) }

    /** Returns false once saving ends, including when this write reaches the cap. */
    fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        if (closed) return false
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        val size = minOf(length.toLong(), maxBytes - bytesWritten).toInt()
        output.write(data, offset, size)
        bytesWritten += size
        if (bytesWritten == maxBytes) close()
        return !closed
    }

    override fun close() {
        if (closed) return
        closed = true
        output.close()
    }
}
