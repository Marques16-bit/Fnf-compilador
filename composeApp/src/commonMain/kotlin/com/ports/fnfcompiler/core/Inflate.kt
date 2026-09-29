package com.ports.fnfcompiler.core

class ArchiveException(message: String) : Exception(message)

internal class Inflate(private val src: ByteArray, private var pos: Int, expected: Int) {
    private var bitBuf = 0
    private var bitCnt = 0
    private var out = ByteArray(if (expected in 1..MAX_OUTPUT) expected else 1024)
    private var outLen = 0

    private class Huffman(lengths: IntArray, n: Int) {
        val count = IntArray(MAX_BITS + 1)
        val symbol = IntArray(n)

        init {
            for (i in 0 until n) count[lengths[i]]++
            val offsets = IntArray(MAX_BITS + 2)
            for (len in 1 until MAX_BITS) offsets[len + 1] = offsets[len] + count[len]
            for (i in 0 until n) {
                val len = lengths[i]
                if (len != 0) symbol[offsets[len]++] = i
            }
        }
    }

    private fun bits(need: Int): Int {
        var value = bitBuf
        while (bitCnt < need) {
            if (pos >= src.size) throw ArchiveException("Unexpected end of compressed data")
            value = value or ((src[pos++].toInt() and 0xFF) shl bitCnt)
            bitCnt += 8
        }
        bitBuf = value ushr need
        bitCnt -= need
        return value and ((1 shl need) - 1)
    }

    private fun decode(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..MAX_BITS) {
            code = code or bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first += count
            first = first shl 1
            code = code shl 1
        }
        throw ArchiveException("Invalid Huffman code")
    }

    private fun put(b: Int) {
        if (outLen == out.size) {
            if (out.size >= MAX_OUTPUT) throw ArchiveException("Entry is too large")
            out = out.copyOf(minOf(out.size * 2L, MAX_OUTPUT.toLong()).toInt())
        }
        out[outLen++] = b.toByte()
    }

    private fun stored() {
        bitBuf = 0
        bitCnt = 0
        if (pos + 4 > src.size) throw ArchiveException("Truncated stored block")
        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        val inv = (src[pos + 2].toInt() and 0xFF) or ((src[pos + 3].toInt() and 0xFF) shl 8)
        if (len != (inv.inv() and 0xFFFF)) throw ArchiveException("Corrupt stored block")
        pos += 4
        if (pos + len > src.size) throw ArchiveException("Truncated stored block")
        for (i in 0 until len) put(src[pos + i].toInt() and 0xFF)
        pos += len
    }

    private fun codes(lit: Huffman, dist: Huffman) {
        while (true) {
            var sym = decode(lit)
            if (sym < 256) {
                put(sym)
            } else if (sym == 256) {
                return
            } else {
                sym -= 257
                if (sym >= 29) throw ArchiveException("Invalid length symbol")
                val len = LEN_BASE[sym] + bits(LEN_EXTRA[sym])
                val ds = decode(dist)
                if (ds >= 30) throw ArchiveException("Invalid distance symbol")
                val d = DIST_BASE[ds] + bits(DIST_EXTRA[ds])
                if (d > outLen) throw ArchiveException("Distance too far back")
                for (i in 0 until len) put(out[outLen - d].toInt() and 0xFF)
            }
        }
    }

    private fun fixed() {
        val lengths = IntArray(288)
        for (i in 0 until 144) lengths[i] = 8
        for (i in 144 until 256) lengths[i] = 9
        for (i in 256 until 280) lengths[i] = 7
        for (i in 280 until 288) lengths[i] = 8
        val lit = Huffman(lengths, 288)
        val distLengths = IntArray(30) { 5 }
        codes(lit, Huffman(distLengths, 30))
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        if (nlen > 286 || ndist > 30) throw ArchiveException("Bad dynamic block counts")
        val lengths = IntArray(19)
        for (i in 0 until ncode) lengths[CL_ORDER[i]] = bits(3)
        val cl = Huffman(lengths, 19)
        val all = IntArray(nlen + ndist)
        var i = 0
        while (i < nlen + ndist) {
            val sym = decode(cl)
            if (sym < 16) {
                all[i++] = sym
            } else {
                var prev = 0
                val rep: Int
                when (sym) {
                    16 -> {
                        if (i == 0) throw ArchiveException("Repeat without previous length")
                        prev = all[i - 1]
                        rep = 3 + bits(2)
                    }
                    17 -> rep = 3 + bits(3)
                    else -> rep = 11 + bits(7)
                }
                if (i + rep > nlen + ndist) throw ArchiveException("Too many lengths")
                repeat(rep) { all[i++] = prev }
            }
        }
        val lit = Huffman(all.copyOfRange(0, nlen), nlen)
        val dist = Huffman(all.copyOfRange(nlen, nlen + ndist), ndist)
        codes(lit, dist)
    }

    fun run(): ByteArray {
        var last: Int
        do {
            last = bits(1)
            when (bits(2)) {
                0 -> stored()
                1 -> fixed()
                2 -> dynamic()
                else -> throw ArchiveException("Invalid block type")
            }
        } while (last == 0)
        return out.copyOf(outLen)
    }

    companion object {
        private const val MAX_BITS = 15
        private const val MAX_OUTPUT = 256 * 1024 * 1024
        private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
        private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        private val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
    }
}
