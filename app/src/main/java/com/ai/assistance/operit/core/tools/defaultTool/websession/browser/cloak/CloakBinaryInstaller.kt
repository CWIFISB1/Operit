package com.ai.assistance.operit.core.tools.defaultTool.websession.browser.cloak

import android.content.Context
import com.ai.assistance.operit.util.AppLogger

/**
 * CloakBrowser 内核自动获取。
 *
 * 逻辑与官方 Python/JS wrapper 一致（cloakbrowser 0.6.4 / config.py + download.py）：
 *   下载地址  {BASE}/chromium-v{version}/cloakbrowser-{platform}.tar.gz
 *   安装路径  {CACHE}/chromium-{version}/chrome
 *   BASE      https://cloakbrowser.dev
 *
 * 平台版本对照（官方 PLATFORM_CHROMIUM_VERSIONS）：
 *   linux-arm64 → 146.0.7680.177.3
 *   linux-x64   → 146.0.7680.177.5
 *
 * 为什么必须拿到真二进制：
 *   Playwright 的 Chromium 只能通过启动 flag 做表面伪装，navigator.webdriver、WebGL
 *   renderer、字体列表这些值仍是「无头/Linux」口径，reCAPTCHA Enterprise、DataDome
 *   这类评分系统会直接判机器人。CloakBrowser 是源码级补丁（ARM64 免费版 58 处），
 *   实测 navigator.webdriver=false、platform=Win32、cores=8、plugins=5。
 *
 * 设计取舍：
 *   - 不内嵌进 APK。二进制解压后 656MB，塞进 APK 会让包体失控、且要为每个 ABI 各带一份。
 *   - 首次使用时按需拉取，之后命中缓存直接复用。
 *   - 全程走 proot 里的 curl/tar，不依赖 Android 侧网络栈，也不受 App 签名影响。
 */
internal object CloakBinaryInstaller {

    private const val TAG = "CloakBinary"

    /** 官方下载站，对应 config.py 的 DOWNLOAD_BASE_URL。 */
    private const val BASE_URL = "https://cloakbrowser.dev"

    /** 缓存根目录，对应 config.py 的 get_cache_dir() 默认值 ~/.cloakbrowser。 */
    private const val CACHE_DIR = "/root/.cloakbrowser"

    /** 官方 PLATFORM_CHROMIUM_VERSIONS 的 arm64 条目。 */
    private const val VERSION_ARM64 = "146.0.7680.177.3"
    /** 官方 PLATFORM_CHROMIUM_VERSIONS 的 x64 条目。 */
    private const val VERSION_X64 = "146.0.7680.177.5"

    private const val MIN_EXPECTED_BYTES = 200L * 1024 * 1024

    /**
     * 解析本机平台对应的官方版本号。
     * 目前 proot 只跑 aarch64，x64 分支保留是为了将来换设备时不用改代码。
     */
    private fun versionForArch(arch: String): String =
        if (arch.contains("x86_64") || arch.contains("amd64")) VERSION_X64 else VERSION_ARM64

    /** 平台标签，对应官方 get_platform_tag()。 */
    private fun platformTagForArch(arch: String): String =
        if (arch.contains("x86_64") || arch.contains("amd64")) "linux-x64" else "linux-arm64"

    /** 二进制绝对路径，对应官方 get_binary_path()。 */
    fun binaryPath(version: String): String = "$CACHE_DIR/chromium-$version/chrome"

    /** 已安装则返回路径，否则 null。 */
    fun installedPath(context: Context, exec: (Context, String, Long) -> String?): String? {
        val arch = exec(context, "uname -m 2>/dev/null", 10_000L)?.trim().orEmpty()
        val version = versionForArch(arch)
        val path = binaryPath(version)
        val ok = exec(context, "[ -x $path ] && stat -c %s $path 2>/dev/null", 15_000L)
            ?.trim()?.toLongOrNull()
        if (ok != null && ok > MIN_EXPECTED_BYTES) {
            AppLogger.i(TAG, "内核已就位: $path ($ok bytes)")
            return path
        }
        return null
    }

    /**
     * 确保内核可用：已装直接返回，未装则下载解压。
     * 返回 null 表示失败（调用方应降级到 Playwright Chromium 并提示）。
     */
    fun ensure(context: Context, exec: (Context, String, Long) -> String?): String? {
        installedPath(context, exec)?.let { return it }

        val arch = exec(context, "uname -m 2>/dev/null", 10_000L)?.trim().orEmpty()
        val version = versionForArch(arch)
        val tag = platformTagForArch(arch)
        val archive = "cloakbrowser-$tag.tar.gz"
        val url = "$BASE_URL/chromium-v$version/$archive"
        val targetDir = "$CACHE_DIR/chromium-$version"

        AppLogger.i(TAG, "开始拉取 CloakBrowser 内核: $url")

        // 先探连通性与体积，避免下到一半才发现是 404 页面。
        exec(context, "mkdir -p $CACHE_DIR", 15_000L)
        val head = exec(context, "curl -sIL -m 30 $url | grep -i '^content-length' | tail -1", 45_000L)
            ?.trim().orEmpty()
        AppLogger.i(TAG, "远端大小头: $head")

        // -L 跟随重定向（cloakbrowser.dev 会 302 到 CDN），-C - 断点续传，
        // --retry 3 抵御 CDN 抖动。tar 直接流式解压，不落中间包。
        val cmd =
            "cd $CACHE_DIR && " +
                "curl -fL --retry 3 --retry-delay 2 -m 1800 -o $archive.tmp $url && " +
                "mkdir -p $targetDir && " +
                "tar -xzf $archive.tmp -C $targetDir --strip-components=1 && " +
                "rm -f $archive.tmp && " +
                "chmod +x $targetDir/chrome && " +
                "stat -c %s $targetDir/chrome"

        val out = exec(context, cmd, 1_900_000L)
        val size = out?.trim()?.lineSequence()?.lastOrNull { it.trim().toLongOrNull() != null }
            ?.trim()?.toLongOrNull()

        return if (size != null && size > MIN_EXPECTED_BYTES) {
            AppLogger.i(TAG, "内核拉取完成: ${binaryPath(version)} ($size bytes)")
            binaryPath(version)
        } else {
            AppLogger.w(TAG, "内核拉取失败，output=${out?.take(400)}")
            // 收尸，避免半截文件把 installedPath 误判成已安装。
            exec(context, "rm -rf $targetDir $CACHE_DIR/$archive.tmp", 30_000L)
            null
        }
    }

    /** 供状态接口展示。 */
    fun describe(context: Context, exec: (Context, String, Long) -> String?): String {
        val arch = exec(context, "uname -m 2>/dev/null", 10_000L)?.trim().orEmpty()
        val version = versionForArch(arch)
        val tag = platformTagForArch(arch)
        val path = binaryPath(version)
        val installed = installedPath(context, exec) != null
        return buildString {
            append("cloak_binary=").append(if (installed) "installed" else "missing").append('\n')
            append("cloak_binary_path=").append(path).append('\n')
            append("cloak_binary_version=").append(version).append('\n')
            append("cloak_binary_platform=").append(tag).append('\n')
            append("cloak_binary_url=").append("$BASE_URL/chromium-v$version/cloakbrowser-$tag.tar.gz")
        }
    }
}
