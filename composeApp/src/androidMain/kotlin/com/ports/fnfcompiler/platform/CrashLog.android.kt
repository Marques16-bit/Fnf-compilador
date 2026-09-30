package com.ports.fnfcompiler.platform

import android.content.Context
import java.io.File

object AppContext {
    private var directory: File? = null

    fun init(context: Context) {
        directory = context.applicationContext.filesDir
    }

    val crashFile: File?
        get() = directory?.let { File(it, "crash.log") }
}

actual fun installCrashHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            AppContext.crashFile?.writeText(formatCrash(error))
        } catch (ignored: Exception) {
        }
        previous?.uncaughtException(thread, error)
    }
}

actual fun readCrashLog(): String? = try {
    AppContext.crashFile?.takeIf { it.exists() }?.readText()?.ifBlank { null }
} catch (e: Exception) {
    null
}

actual fun clearCrashLog() {
    try {
        AppContext.crashFile?.delete()
    } catch (ignored: Exception) {
    }
}
