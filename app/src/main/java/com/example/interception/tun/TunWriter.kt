package com.example.interception.tun

/**
 * Serializes writes to the tun fd. The fd is shared between the read side ([PacketPump]'s loop)
 * and every relay flow's reply path, so all writes must go through one lock — otherwise two
 * concurrent writers could interleave partial datagrams on the same descriptor.
 *
 * Deliberately sink-based (not `FileOutputStream`-typed) so unit tests can supply a
 * `ByteArrayOutputStream`-backed fake sink without needing a real tun fd; production wraps the
 * real `FileOutputStream` in a one-line [TunSink].
 */
fun interface TunSink {
    fun write(datagram: ByteArray)
}

class TunWriter(private val sink: TunSink) {
    private val lock = Any()

    fun write(datagram: ByteArray) {
        synchronized(lock) {
            sink.write(datagram)
        }
    }
}
