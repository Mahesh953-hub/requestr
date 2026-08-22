package com.example.interception.tun

/**
 * Bounded kernel-id -> guest-(id,seq) mapping table for [IcmpRelay]'s ping-socket path.
 *
 * Extracted into its own small class purely so the id<->id mapping and its eviction policy can
 * be unit-tested directly (task 6's test plan: "id<->id mapping round-trip and eviction") without
 * needing any real socket, since the primary ping-socket path itself cannot be exercised in a
 * plain JVM unit test (no ping socket in the test JVM).
 */
class IcmpIdMap(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    data class Entry(
        val guestId: Int,
        val guestSeq: Int,
        val family: Int,
        val guestIp: ByteArray,
        val targetIp: ByteArray,
        val createdAt: Long,
    )

    private val map = LinkedHashMap<Int, Entry>()
    private val lock = Object()

    /** Records that [kernelId] (the identifier the kernel actually assigned to our ping socket)
     * stands in for [entry]'s guest-originated (id, seq) pair. Evicts the oldest entry first if
     * the table is already at [maxEntries]. */
    fun put(kernelId: Int, entry: Entry) {
        synchronized(lock) {
            if (map.size >= maxEntries && !map.containsKey(kernelId)) {
                val oldest = map.entries.minByOrNull { it.value.createdAt }
                if (oldest != null) map.remove(oldest.key)
            }
            map[kernelId] = entry
        }
    }

    /** Looks up and removes the entry for [kernelId] -- a reply is a one-shot match, not a
     * durable subscription. Returns null if [kernelId] was never recorded or was already
     * evicted/consumed. */
    fun remove(kernelId: Int): Entry? = synchronized(lock) { map.remove(kernelId) }

    fun size(): Int = synchronized(lock) { map.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
