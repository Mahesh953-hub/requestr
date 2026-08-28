package com.example.scripting

/** One `pm.test(name, fn)` outcome recorded by a script run via [PmBridgeBase.recordTest]. */
data class PmTestResult(
    val name: String,
    val passed: Boolean,
    val message: String,
)
