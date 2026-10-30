package com.nuvio.app.core.logging

// Gate-only stand-in for the app's InAppLogger (which needs a platform
// expect/actual for timestamps and is not copied into the gate project).
// Same object name and debug/info/warn/error signatures the copied reader
// calls, printing to stdout so stage logs appear in the gate run's log.
object InAppLogger {
    fun debug(tag: String, message: String) {
        println("[$tag] $message")
    }

    fun info(tag: String, message: String) {
        println("[$tag] $message")
    }

    fun warn(tag: String, message: String) {
        println("[$tag] $message")
    }

    fun error(tag: String, message: String) {
        println("[$tag] $message")
    }
}
