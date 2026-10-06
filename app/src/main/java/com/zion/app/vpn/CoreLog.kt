package com.zion.app.vpn

import java.io.File
import java.io.RandomAccessFile

/**
 * The core's own warnings and errors (the Windows version reads them from the core's stderr). The core
 * writes them to a file; new lines are handed to the event log, and the last problem explains a failed start.
 */
class CoreLog(val file: File) {
    private var offset = 0L

    fun reset() {
        file.delete()
        offset = 0
    }

    /** Lines written since the last call, cleaned for reading. */
    fun newLines(): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < offset) offset = 0 // the file was started over
                raf.seek(offset)
                val bytes = ByteArray((raf.length() - offset).coerceAtMost(256 * 1024).toInt())
                raf.readFully(bytes)
                // Only whole lines: a line still being written waits for the next call
                val text = String(bytes, Charsets.UTF_8)
                val end = text.lastIndexOf('\n')
                if (end < 0) return emptyList()
                offset += text.substring(0, end + 1).toByteArray(Charsets.UTF_8).size
                text.substring(0, end).split('\n').map { clean(it) }.filter { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The core's last error (or warning) in the whole file, or null. */
    fun lastProblem(): String? = try {
        if (!file.exists()) null
        else file.readLines(Charsets.UTF_8).map { clean(it) }.lastOrNull { it.startsWith("ERROR") || it.startsWith("FATAL") }
            ?: file.readLines(Charsets.UTF_8).map { clean(it) }.lastOrNull { it.startsWith("WARN") }
    } catch (_: Exception) {
        null
    }

    companion object {
        private val ansi = Regex("\u001B\\[[0-9;]*m")
        private val level = Regex("\\b(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|PANIC)\\b")

        /** "+0700 2026-10-06 06:22:57 ERROR [123 1.2s] outbound/x: y" → "ERROR outbound/x: y". */
        fun clean(raw: String): String {
            val s = ansi.replace(raw, "").trim()
            val m = level.find(s) ?: return s
            var rest = s.substring(m.range.last + 1).trim()
            if (rest.startsWith("[")) rest = rest.substringAfter("] ", rest).trim()
            return "${m.value} $rest"
        }
    }
}
