package com.ports.fnfcompiler.platform

expect fun installCrashHandler()

expect fun readCrashLog(): String?

expect fun clearCrashLog()

fun formatCrash(error: Throwable): String = error.stackTraceToString().take(3_000)
