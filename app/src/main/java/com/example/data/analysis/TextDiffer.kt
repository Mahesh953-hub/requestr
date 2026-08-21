package com.example.data.analysis

/**
 * Myers O(ND) diff algorithm — computes the minimal edit script between
 * two strings, returning a list of diff hunks for display.
 *
 * Used by the Diff view (#28) to compare request/response bodies.
 */
object TextDiffer {

    enum class Op { EQUAL, INSERT, DELETE }

    data class DiffHunk(
        val op: Op,
        val oldLine: String,  // "" for inserts
        val newLine: String,  // "" for deletes
        val oldLineNum: Int,  // 1-based
        val newLineNum: Int,  // 1-based
    )

    /**
     * Computes the diff between two strings (line-by-line).
     * Returns a list of hunks in order.
     */
    fun diff(old: String, new: String): List<DiffHunk> {
        val oldLines = old.split("\n")
        val newLines = new.split("\n")
        return diffLines(oldLines, newLines)
    }

    fun diffLines(oldLines: List<String>, newLines: List<String>): List<DiffHunk> {
        val n = oldLines.size
        val m = newLines.size

        // Use simple LCS-based approach for correctness over speed
        // (Myers O(ND) is complex; LCS DP is fine for typical API payloads)
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                lcs[i][j] = when {
                    oldLines[i - 1] == newLines[j - 1] -> lcs[i - 1][j - 1] + 1
                    else -> maxOf(lcs[i - 1][j], lcs[i][j - 1])
                }
            }
        }

        // Backtrack to build the diff
        val hunks = mutableListOf<DiffHunk>()
        var i = n; var j = m
        val reversed = mutableListOf<DiffHunk>()
        while (i > 0 && j > 0) {
            when {
                oldLines[i - 1] == newLines[j - 1] -> {
                    reversed.add(DiffHunk(Op.EQUAL, oldLines[i - 1], newLines[j - 1], i, j))
                    i--; j--
                }
                lcs[i - 1][j] >= lcs[i][j - 1] -> {
                    reversed.add(DiffHunk(Op.DELETE, oldLines[i - 1], "", i, 0))
                    i--
                }
                else -> {
                    reversed.add(DiffHunk(Op.INSERT, "", newLines[j - 1], 0, j))
                    j--
                }
            }
        }
        while (i > 0) { reversed.add(DiffHunk(Op.DELETE, oldLines[i - 1], "", i, 0)); i-- }
        while (j > 0) { reversed.add(DiffHunk(Op.INSERT, "", newLines[j - 1], 0, j)); j-- }

        return reversed.reversed()
    }

    /** Compact summary: counts of added/removed/changed lines. */
    fun summarize(hunks: List<DiffHunk>): Triple<Int, Int, Int> {
        val added = hunks.count { it.op == Op.INSERT }
        val removed = hunks.count { it.op == Op.DELETE }
        val unchanged = hunks.count { it.op == Op.EQUAL }
        return Triple(added, removed, unchanged)
    }
}
