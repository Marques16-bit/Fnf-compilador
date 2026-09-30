package com.ports.fnfcompiler.core

import com.russhwolf.settings.Settings

data class PendingBuild(val tag: String, val target: BuildTarget, val repo: String, val branch: String)

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

    fun loadPending(): PendingBuild? {
        val tag = settings.getString(PENDING_TAG, "")
        val target = BuildTarget.entries.firstOrNull { it.id == settings.getString(PENDING_TARGET, "") }
        if (tag.isEmpty() || target == null) return null
        return PendingBuild(tag, target, settings.getString(PENDING_REPO, ""), settings.getString(PENDING_BRANCH, ""))
    }

    fun savePending(pending: PendingBuild) {
        settings.putString(PENDING_TAG, pending.tag)
        settings.putString(PENDING_TARGET, pending.target.id)
        settings.putString(PENDING_REPO, pending.repo)
        settings.putString(PENDING_BRANCH, pending.branch)
    }

    fun clearPending() {
        settings.remove(PENDING_TAG)
        settings.remove(PENDING_TARGET)
        settings.remove(PENDING_REPO)
        settings.remove(PENDING_BRANCH)
    }

    private companion object {
        const val URL = "url"
        const val TOKEN = "token"
        const val REPO = "repo"
        const val BRANCH = "branch"
        const val TARGET = "target"
        const val PENDING_TAG = "pending.tag"
        const val PENDING_TARGET = "pending.target"
        const val PENDING_REPO = "pending.repo"
        const val PENDING_BRANCH = "pending.branch"
    }
}
