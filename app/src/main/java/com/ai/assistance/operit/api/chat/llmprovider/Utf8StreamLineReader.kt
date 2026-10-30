package com.ai.assistance.operit.api.chat.llmprovider

import java.io.InputStream

/**
 * UTF-8 安全的流式按行读取器。
 *
 * 背景：BufferedReader.readLine() 在某些情况下会在 UTF-8 多字节字符
 * 中间切断（例如服务端 chunk 恰好切开中文三字节序列），导致解码出替换字符。
 *
 * 本实现严格按字节累积，只在遇到 \n 时切分整行，然后对整行做 UTF-8 解码，
 * 保证任何传输分片边界都不会破坏多字节字符。
 */
internal class Utf8StreamLineReader(
    private val input: InputStream,
    private val maxLineBytes: Int = 8 * 1024 * 1024
) {
    private val pending = java.io.ByteArrayOutputStream(8192)
    private val buffer = ByteArray(8192)
    private var eof = false

    /**
     * 读取下一整行（不含 \n）。
     * @return 行内容；流结束时返回 null
     */
    fun readLine(): String? {
        while (true) {
            // 先看 pending 里是否已有完整行
            val bytes = pending.toByteArray()
            val nl = bytes.indexOf('\n'.code.toByte())
            if (nl >= 0) {
                val lineBytes = bytes.copyOfRange(0, nl)
                val rest = bytes.copyOfRange(nl + 1, bytes.size)
                pending.reset()
                pending.write(rest)
                return decodeLine(lineBytes)
            }
            if (eof) {
                // 流已结束，处理剩余字节
                if (pending.size() == 0) return null
                val tail = pending.toByteArray()
                pending.reset()
                return decodeLine(tail)
            }
            // 继续读
            val n = try {
                input.read(buffer)
            } catch (e: java.io.IOException) {
                eof = true
                continue
            }
            if (n < 0) {
                eof = true
                continue
            }
            if (n > 0) {
                pending.write(buffer, 0, n)
                if (pending.size() > maxLineBytes) {
                    throw IllegalStateException("流式行超过最大长度 $maxLineBytes 字节")
                }
            }
        }
    }

    /** 严格解码整行，容忍行尾 \r。 */
    private fun decodeLine(raw: ByteArray): String {
        val end = if (raw.isNotEmpty() && raw[raw.size - 1] == '\r'.code.toByte()) raw.size - 1 else raw.size
        return String(raw, 0, end, Charsets.UTF_8)
    }


    /**
     * 与 BufferedReader.useLines 兼容：逐行回调，结束后自动关闭。
     */
    inline fun useLines(block: (Sequence<String>) -> Unit) {
        try {
            block(generateSequence { readLine() })
        } finally {
            close()
        }
    }

    fun close() {
        runCatching { input.close() }
    }
}
