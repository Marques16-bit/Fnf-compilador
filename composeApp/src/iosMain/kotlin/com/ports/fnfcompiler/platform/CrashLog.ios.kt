package com.ports.fnfcompiler.platform

import platform.Foundation.NSUserDefaults
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

private const val CRASH_KEY = "fnfcompiler.crash"

@OptIn(ExperimentalNativeApi::class)
actual fun installCrashHandler() {
    setUnhandledExceptionHook { error ->
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject(formatCrash(error), CRASH_KEY)
        defaults.synchronize()
    }
}

actual fun readCrashLog(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(CRASH_KEY)?.ifBlank { null }

actual fun clearCrashLog() {
    NSUserDefaults.standardUserDefaults.removeObjectForKey(CRASH_KEY)
}
