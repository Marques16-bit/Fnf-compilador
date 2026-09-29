package com.ports.fnfcompiler.core

interface SourceFiles {
    val names: List<String>
    val scanCap: Int
    fun size(name: String): Long
    suspend fun text(name: String): String
}

class ZipSource(val fileName: String, val bytes: ByteArray) : SourceFiles {
    private val archive = ZipArchive(bytes)
    private val files = archive.entries.filter { !it.isDirectory }.associateBy { it.name }

    override val names: List<String> = files.keys.toList()
    override val scanCap: Int = 1500

    override fun size(name: String): Long = files[name]?.size ?: 0L

    override suspend fun text(name: String): String {
        val entry = files[name] ?: throw ArchiveException("$name is not in the archive")
        return archive.readText(entry)
    }
}
