package com.example.interception

import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Non-root UID attribution by reading `/proc/net/tcp` / `/proc/net/tcp6` directly.
 *
 * Unlike the classic "walk `/proc/<pid>/fd` looking for a symlink to this socket's inode" trick
 * (which needs the inode *and* a second pass over every process, i.e. effectively root on modern
 * Android), this needs nothing else: the kernel stamps the owning UID directly onto every TCP
 * socket row (`/proc/net/tcp`'s `uid` column, 8th whitespace-separated field), the same field
 * `netstat`/`lsof -n` read. So this is a single read + line match, no root, no second lookup.
 *
 * What this *cannot* work around: Android's SELinux policy has restricted `/proc/net/tcp*`
 * visibility per-app since Nougat (a regular app process generally only sees its own sockets
 * there, not other apps'). On such a device this legitimately returns null for another app's
 * connection -- same failure mode as `ConnectivityManager.getConnectionOwnerUid` returning -1
 * when it doesn't apply. It works when requestr runs with elevated privileges (root shell caller
 * reads unrestricted), on older/less-restricted builds, or for requestr's own sockets.
 */
internal object ProcNetUidResolver {

    private val DEFAULT_TCP_FILES = listOf(File("/proc/net/tcp"), File("/proc/net/tcp6"))

    /** Real read of `/proc/net/tcp`+`tcp6`, matching a row whose (local, remote) equals the pair given. */
    fun resolve(local: InetSocketAddress, remote: InetSocketAddress, files: List<File> = DEFAULT_TCP_FILES): Int? {
        for (file in files) {
            val uid = runCatching {
                if (!file.canRead()) return@runCatching null
                resolveFromLines(file.readLines(), local, remote)
            }.getOrNull()
            if (uid != null) return uid
        }
        return null
    }

    /**
     * Pure parsing logic, split out from [resolve] so it is unit-testable against fixture text
     * without depending on the host's actual `/proc/net/tcp` contents (which vary by machine and
     * contain nothing relevant to a fake test socket pair anyway).
     */
    internal fun resolveFromLines(lines: List<String>, local: InetSocketAddress, remote: InetSocketAddress): Int? {
        val localHex = encode(local) ?: return null
        val remoteHex = encode(remote) ?: return null
        for (line in lines.drop(1)) { // first line is the column header
            val cols = line.trim().split(Regex("\\s+"))
            // sl local_address rem_address st tx:rx tr:tm retrnsmt uid timeout inode ...
            if (cols.size < 8) continue
            if (cols[1].equals(localHex, ignoreCase = true) && cols[2].equals(remoteHex, ignoreCase = true)) {
                return cols[7].toIntOrNull()
            }
        }
        return null
    }

    /** `"<hex-ip>:<hex-port>"`, matching /proc/net/tcp*'s own encoding -- see [encodeV4]/[encodeV6]. */
    private fun encode(addr: InetSocketAddress): String? {
        val ip = addr.address ?: return null
        val hostHex = when (ip) {
            is Inet4Address -> encodeV4(ip.address)
            is Inet6Address -> encodeV6(ip.address)
            else -> return null
        }
        val portHex = "%04X".format(addr.port)
        return "$hostHex:$portHex"
    }

    /** IPv4: 4 bytes, byte-reversed (kernel stores the raw `in_addr` as a little-endian word). */
    private fun encodeV4(bytes: ByteArray): String =
        (3 downTo 0).joinToString("") { i -> "%02X".format(bytes[i].toInt() and 0xFF) }

    /** IPv6: 16 bytes as four 4-byte groups, each group individually byte-reversed. */
    private fun encodeV6(bytes: ByteArray): String =
        (0 until 4).joinToString("") { group ->
            (3 downTo 0).joinToString("") { i -> "%02X".format(bytes[group * 4 + i].toInt() and 0xFF) }
        }
}
