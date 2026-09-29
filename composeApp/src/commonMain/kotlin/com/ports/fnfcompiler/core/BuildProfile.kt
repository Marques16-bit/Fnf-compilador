package com.ports.fnfcompiler.core

data class BuildProfile(
    val haxe: String,
    val defines: List<String>,
    val modern: Boolean,
    val recipe: String = "generic"
) {
    companion object {
        val Legacy = BuildProfile("4.2.5", emptyList(), false)
    }
}
