package com.ai.assistance.operit.core.opencode

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * opencode 二进制自愈落地器。
 *
 * 背景：opencode 真实客户端是 zenfree 网关的传输层进程（`opencode serve`）。
 * 它必须存在于应用私有数据区，原因有两个：
 *  1. /sdcard 是 noexec，无法直接执行 ELF
 *  2. proot rootfs 在重装 App / 清数据时会被重置，二进制会丢失
 *
 * 因此落在 filesDir/opencode/opencode：重装不清空、可执行、proot 内通过 -b /data 可见。
 *
 * 来源优先级：
 *  1. 已就位且版本号一致 → 直接返回
 *  2. assets/opencode.gz（若构建时打包）→ 流式解压
 *  3. /sdcard/1/opencode_extracted/opencode/opencode（官方包手动解压位置）
 */
object OpencodeBootstrap {
    private const val TAG = "OpencodeBootstrap"
    private const val ASSET_GZ = "opencode.gz"
    private const val BINARY_VERSION = "202608081117"
    private const val BUFFER_SIZE = 1 shl 16

    private val candidateSources = listOf(
        "/sdcard/1/opencode_extracted/opencode/opencode",
        "/storage/emulated/0/1/opencode_extracted/opencode/opencode",
        "/data/media/0/1/opencode_extracted/opencode/opencode",
    )

    fun binDir(context: Context): File = File(context.filesDir, "opencode")

    fun binaryFile(context: Context): File = File(binDir(context), "opencode")

    /** proot 场景使用的绝对路径。 */
    fun binaryPath(context: Context): String = binaryFile(context).absolutePath

    fun isReady(context: Context): Boolean {
        val bin = binaryFile(context)
        return bin.isFile && bin.length() > 0L && bin.canExecute()
    }

    /**
     * 确保二进制就位。幂等，可重复调用。
     * 全部来源都失败时返回 null，不抛异常，避免拖垮启动流程。
     */
    fun ensure(context: Context, onLog: (String) -> Unit = {}): File? {
        val bin = binaryFile(context)
        val stamp = File(binDir(context), "VERSION")

        if (isReady(context) && readStamp(stamp) == BINARY_VERSION) {
            return bin
        }

        binDir(context).mkdirs()

        if (extractFromAssets(context, bin, onLog)) {
            markReady(bin, stamp, "assets", onLog)
            return bin
        }

        if (copyFromExternal(bin, onLog)) {
            markReady(bin, stamp, "external", onLog)
            return bin
        }

        if (isReady(context)) {
            AppLogger.w(TAG, "opencode 已存在但版本标记不匹配，沿用现有文件")
            return bin
        }

        AppLogger.w(TAG, "opencode 落地失败：无内置资源且外部备份不存在")
        onLog("opencode 未就位，请把官方二进制放到 /sdcard/1/opencode_extracted/opencode/opencode")
        return null
    }

    private fun readStamp(file: File): String? =
        try {
            if (file.isFile) file.readText().trim() else null
        } catch (_: Throwable) {
            null
        }

    private fun markReady(bin: File, stamp: File, source: String, onLog: (String) -> Unit) {
        bin.setExecutable(true, false)
        try {
            stamp.writeText(BINARY_VERSION)
        } catch (_: Throwable) {
        }
        AppLogger.i(TAG, "opencode ready source=$source size=${bin.length()}")
        onLog("opencode 已就位（$source，${bin.length() / 1024 / 1024}MB）")
    }

    private fun extractFromAssets(
        context: Context,
        target: File,
        onLog: (String) -> Unit,
    ): Boolean {
        val available =
            try {
                context.assets.open(ASSET_GZ).close()
                true
            } catch (_: Throwable) {
                false
            }
        if (!available) return false

        return try {
            onLog("正在解压内置 opencode")
            val tmp = File(target.parentFile, target.name + ".tmp")
            context.assets.open(ASSET_GZ).use { raw ->
                GZIPInputStream(raw, BUFFER_SIZE).use { gz ->
                    FileOutputStream(tmp).use { out -> gz.copyTo(out, BUFFER_SIZE) }
                }
            }
            if (tmp.length() <= 0L) {
                tmp.delete()
                return false
            }
            replaceFile(tmp, target)
            true
        } catch (error: Throwable) {
            AppLogger.e(TAG, "解压内置 opencode 失败", error)
            false
        }
    }

    private fun copyFromExternal(target: File, onLog: (String) -> Unit): Boolean {
        for (path in candidateSources) {
            val src = File(path)
            if (!src.isFile || src.length() <= 0L) continue
            return try {
                onLog("正在从外部备份复制 opencode")
                val tmp = File(target.parentFile, target.name + ".tmp")
                src.copyTo(tmp, overwrite = true)
                if (tmp.length() <= 0L) {
                    tmp.delete()
                    return false
                }
                replaceFile(tmp, target)
                true
            } catch (error: Throwable) {
                AppLogger.e(TAG, "复制 opencode 失败 from=" + path, error)
                false
            }
        }
        return false
    }

    private fun replaceFile(from: File, to: File) {
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }
}