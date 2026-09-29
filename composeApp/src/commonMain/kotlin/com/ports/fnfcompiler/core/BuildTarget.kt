package com.ports.fnfcompiler.core

enum class BuildTarget(
    val id: String,
    val label: String,
    val output: String,
    val mobile: Boolean
) {
    ANDROID("android", "Android", "APK", true),
    IOS("ios", "iOS", "IPA", true),
    WINDOWS("windows", "Windows", "EXE", false),
    MACOS("macos", "macOS", "APP", false),
    LINUX("linux", "Linux", "BIN", false)
}
