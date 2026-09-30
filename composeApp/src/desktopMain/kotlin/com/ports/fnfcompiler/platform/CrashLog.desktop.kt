package com.ports.fnfcompiler.platform

import java.io.File

private val crashFile: File
    get() = File(File(System.getProperty("user.home"), ".fnfcompiler"), "crash.log")

actual fun installCrashHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            crashFile.parentFile.mkdirs()
            crashFile.writeText(formatCrash(error))
        } catch (ignored: Exception) {
        }
        previous?.uncaughtException(thread, error)
    }
}

actual fun readCrashLog(): String? = try {
    crashFile.takeIf { it.exists() }?.readText()?.ifBlank { null }
} catch (e: Exception) {
    null
}

actual fun clearCrashLog() {
    try {
        crashFile.delete()
    } catch (ignored: Exception) {
    }
}
