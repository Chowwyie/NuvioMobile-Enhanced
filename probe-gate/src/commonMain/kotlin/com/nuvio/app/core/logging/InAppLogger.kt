package com.nuvio.app.core.logging

// Gate-only stand-in for the app's InAppLogger (which needs a platform
// expect/actual for timestamps and is not copied into the gate project).
// Same object name and debug/info/warn/error signatures the copied reader
// calls, printing to stdout so stage logs appear in the gate run's log.
// Lines are also buffered so Main.kt can parse the reader's perf metrics
// (spans/bytes) and assert the modeled 5s gate.
object InAppLogger {
    val lines = mutableListOf<String>()

    fun debug(tag: String, message: String) {
        val line = "[$tag] $message"
        lines.add(line)
        println(line)
    }

    fun info(tag: String, message: String) {
        val line = "[$tag] $message"
        lines.add(line)
        println(line)
    }

    fun warn(tag: String, message: String) {
        val line = "[$tag] $message"
        lines.add(line)
        println(line)
    }

    fun error(tag: String, message: String) {
        val line = "[$tag] $message"
        lines.add(line)
        println(line)
    }
}
