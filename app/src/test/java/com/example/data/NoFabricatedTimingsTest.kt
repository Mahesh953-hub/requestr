package com.example.data

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * S2 task 18 regression guard -- no production source may reintroduce a timing derived from a
 * total (`latency * 0.15` and friends) or the retired `requestr-proxy/2.0` fake server header.
 */
class NoFabricatedTimingsTest {

    private fun mainSources(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("could not locate app/src/main/java from ${File(".").absolutePath}") as Nothing
    }

    private val forbidden = listOf(
        Regex("""latency\s*\*\s*0\."""),
        Regex("""latencyMs\s*\*\s*0\."""),
        Regex("""requestr-proxy/2\.0"""),
    )

    @Test
    fun `no production source scales a total into phase timings or fakes a server header`() {
        val root = mainSources()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().filter { (_, line) -> forbidden.any { it.containsMatchIn(line) } }
                    .map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue("fabricated timings found:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
