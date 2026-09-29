package com.ports.fnfcompiler.core

import com.russhwolf.settings.Settings

class SettingsStore(private val settings: Settings = Settings()) {
    fun load(): BuildConfig = BuildConfig(
        token = settings.getString(TOKEN, ""),
        repo = settings.getString(REPO, ""),
        branch = settings.getString(BRANCH, "")
    )

    fun save(config: BuildConfig) {
        settings.putString(TOKEN, config.token)
        settings.putString(REPO, config.repo)
        settings.putString(BRANCH, config.branch)
    }

    fun loadTarget(): BuildTarget = BuildTarget.entries.firstOrNull { it.id == settings.getString(TARGET, "") } ?: BuildTarget.ANDROID

    fun saveTarget(target: BuildTarget) = settings.putString(TARGET, target.id)

    fun loadUrl(): String = settings.getString(URL, "")

    fun saveUrl(url: String) = settings.putString(URL, url)

    private companion object {
        const val URL = "url"
        const val TOKEN = "token"
        const val REPO = "repo"
        const val BRANCH = "branch"
        const val TARGET = "target"
    }
}
