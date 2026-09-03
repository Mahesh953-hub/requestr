package com.example.interception.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Gate G3: per-flow per-direction buffers stay inside 512 KiB..2 MiB, spilling above the cap. */
class BufferPoolCapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `flow cap is clamped to the 512KiB-2MiB window with a 1MiB default`() {
        assertEquals(1024 * 1024, BufferPool().flowCapBytes)
        assertEquals(512 * 1024, BufferPool(flowCapBytes = 1).flowCapBytes)
        assertEquals(2 * 1024 * 1024, BufferPool(flowCapBytes = Int.MAX_VALUE).flowCapBytes)
        assertEquals(700 * 1024, BufferPool(flowCapBytes = 700 * 1024).flowCapBytes)
    }

    @Test
    fun `copy buffers are recycled and the pool is bounded`() {
        val pool = BufferPool(bufferSize = 64, maxPooled = 2)
        val a = pool.acquire()
        val b = pool.acquire()
        val c = pool.acquire()
        assertEquals(3, pool.allocatedCount)
        pool.release(a); pool.release(b); pool.release(c)
        assertEquals(2, pool.pooledCount) // third is dropped, not hoarded
        assertSame(a, pool.acquire()) // FIFO: released first, reused first
        assertSame(b, pool.acquire())
        pool.release(ByteArray(7)) // wrong size is ignored
        assertEquals(0, pool.pooledCount)
    }

    @Test
    fun `twenty concurrent flows never hold more than cap bytes in memory per direction`() {
        val cap = 512 * 1024
        val pool = BufferPool(flowCapBytes = cap, spillDir = tmp.newFolder("spill"))
        val flows = (1..20).map { pool.newFlowBuffer() }
        val chunk = ByteArray(100 * 1024) { 0x41 }
        repeat(12) { flows.forEach { assertTrue(it.append(chunk)) } } // 1.2 MiB each
        flows.forEach { f ->
            assertTrue("in-memory ${f.inMemorySize} exceeds cap", f.inMemorySize <= cap)
            assertEquals(1_200L * 1024, f.size)
            assertTrue(f.isSpilled)
        }
        assertEquals(20, pool.spillCount)
        val totalInMemory = flows.sumOf { it.inMemorySize.toLong() }
        assertTrue(totalInMemory <= 20L * cap)
        flows.forEach { it.close() }
        assertEquals(0, tmp.root.resolve("spill").listFiles()!!.size)
    }

    @Test
    fun `spilled content round-trips in order`() {
        val pool = BufferPool(flowCapBytes = 512 * 1024, spillDir = tmp.newFolder("s"))
        val f = pool.newFlowBuffer()
        val expected = java.io.ByteArrayOutputStream()
        var seed = 1
        repeat(7) {
            val part = ByteArray(200 * 1024) { (seed++ and 0xFF).toByte() }
            assertTrue(f.append(part))
            expected.write(part)
        }
        assertTrue(f.isSpilled)
        assertArrayEquals(expected.toByteArray(), f.toByteArray())
        f.reset()
        assertEquals(0L, f.size)
        assertFalse(f.isSpilled)
        f.close()
    }

    @Test
    fun `without a spill directory the buffer refuses to exceed the cap`() {
        val pool = BufferPool(flowCapBytes = 512 * 1024, spillDir = null)
        val f = pool.newFlowBuffer()
        assertTrue(f.append(ByteArray(512 * 1024)))
        assertFalse(f.append(ByteArray(1)))
        assertEquals(512L * 1024, f.size)
        f.close()
    }
}
