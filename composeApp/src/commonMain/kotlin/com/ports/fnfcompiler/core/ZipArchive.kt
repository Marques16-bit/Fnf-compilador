package com.ports.fnfcompiler.core

class ZipEntry internal constructor(
    val name: String,
    val size: Long,
    internal val method: Int,
    internal val compressedSize: Long,
    internal val localOffset: Long
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

class ZipArchive(private val data: ByteArray) {
    val entries: List<ZipEntry>

    init {
        val eocd = findEndOfCentralDirectory()
        val count = u16(eocd + 10)
        var offset = u32(eocd + 16)
        if (count == 0xFFFF || offset == 0xFFFFFFFFL) throw ArchiveException("ZIP64 archives are not supported")
        val list = ArrayList<ZipEntry>(count)
        repeat(count) {
            val p = offset.toInt()
            if (p < 0 || p + 46 > data.size || u32(p) != CENTRAL_SIGNATURE) throw ArchiveException("Corrupt central directory")
            val method = u16(p + 10)
            val compressed = u32(p + 20)
            val size = u32(p + 24)
            val nameLen = u16(p + 28)
            val extraLen = u16(p + 30)
            val commentLen = u16(p + 32)
            val local = u32(p + 42)
            if (p + 46 + nameLen > data.size) throw ArchiveException("Corrupt central directory")
            val name = data.decodeToString(p + 46, p + 46 + nameLen).replace('\\', '/')
            list.add(ZipEntry(name, size, method, compressed, local))
            offset += 46L + nameLen + extraLen + commentLen
        }
        entries = list
    }

    fun read(entry: ZipEntry): ByteArray {
        val p = entry.localOffset.toInt()
        if (p < 0 || p + 30 > data.size || u32(p) != LOCAL_SIGNATURE) throw ArchiveException("Corrupt local header for ${entry.name}")
        val start = p + 30 + u16(p + 26) + u16(p + 28)
        val end = start + entry.compressedSize.toInt()
        if (end > data.size) throw ArchiveException("Truncated entry ${entry.name}")
        return when (entry.method) {
            0 -> data.copyOfRange(start, end)
            8 -> Inflate(data, start, entry.size.toInt()).run()
            else -> throw ArchiveException("Unsupported compression method ${entry.method}")
        }
    }

    fun readText(entry: ZipEntry): String = read(entry).decodeToString()

    private fun findEndOfCentralDirectory(): Int {
        var i = data.size - 22
        val limit = maxOf(0, data.size - 22 - 65535)
        while (i >= limit) {
            if (u32(i) == END_SIGNATURE) return i
            i--
        }
        throw ArchiveException("Not a valid ZIP archive")
    }

    private fun u16(p: Int): Int = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)

    private fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)

    private companion object {
        const val END_SIGNATURE = 0x06054b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val LOCAL_SIGNATURE = 0x04034b50L
    }
}
