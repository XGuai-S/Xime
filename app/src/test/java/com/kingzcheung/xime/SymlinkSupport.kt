package com.kingzcheung.xime

import org.junit.Assume
import java.io.File
import java.nio.file.Files

/**
 * 文件系统符号链接能力探测。
 *
 * 多个安全测试（模型路径、恢复事务、插件安装、无线导入）靠真实符号链接验证
 * 「不跟随链接、不越界」的防护。但 **Windows 默认不允许普通用户创建符号链接**
 * （需要 `SeCreateSymbolicLinkPrivilege`，即管理员或开启「开发者模式」），
 * `Files.createSymbolicLink` 会直接抛 `FileSystemException`，测试于是报红——
 * 报的是环境限制，不是被测代码的缺陷。
 *
 * 这些用例在 Linux（CI）与 Android（真机 ext4/f2fs）上能正常执行，覆盖不该降低。
 * 因此这里按环境分流：
 * - 支持符号链接 → 正常执行真实校验；
 * - 不支持 → 用 [Assume] 跳过该用例（显示为 skipped，不是 failed）。
 *
 * 只影响测试，不触碰任何生产代码。
 */
internal object SymlinkSupport {

    /**
     * 当前文件系统/账户能否创建符号链接。
     *
     * 用临时目录实测而不是查系统版本或管理员身份：
     * 「开发者模式」、组策略、卷格式（FAT 不支持）都会影响结果，
     * 只有真正建一次才准确。结果缓存，避免每个用例都建删一次。
     */
    val available: Boolean by lazy { probe() }

    private fun probe(): Boolean {
        return try {
            val dir = Files.createTempDirectory("xime-symlink-probe").toFile()
            try {
                val target = File(dir, "target").apply { writeText("probe") }
                val link = File(dir, "link")
                Files.createSymbolicLink(link.toPath(), target.toPath())
                Files.isSymbolicLink(link.toPath())
            } finally {
                dir.deleteRecursively()
            }
        } catch (_: Exception) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    /** 环境不支持时跳过当前用例。 */
    fun assumeAvailable() {
        Assume.assumeTrue(
            "当前文件系统不允许创建符号链接（Windows 需管理员或开发者模式）；" +
                "该用例在 Linux/Android 上会正常执行",
            available,
        )
    }

    /** 创建符号链接；环境不支持时跳过当前用例（调用前无需再判 [available]）。 */
    fun create(link: File, target: File) {
        assumeAvailable()
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }
}
