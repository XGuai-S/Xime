package com.kingzcheung.xime

import java.io.File

/**
 * 单测用的仓库定位工具。
 *
 * 单测的 workingDir 可能是模块目录（`app/`）也可能是仓库根目录，因此统一逐级向上查找。
 * 原先 5 个测试类各自复制了一份实现（`repoFile` 3 份、`mainSources` 2 份），收敛到此处。
 */
internal object RepoFiles {

    /** 主源码目录（`app/src/main/java` 或 `src/main/java`）下的全部 .kt。 */
    val mainKotlinSources: List<File> by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in listOf("app/src/main/java", "src/main/java")) {
                val root = File(dir, rel)
                if (root.isDirectory) {
                    return@lazy root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                }
            }
            dir = dir.parentFile
        }
        error("未找到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 按仓库相对路径定位文件；相对路径找不到时会自动尝试加 `app/` 前缀。 */
    fun file(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("file not found: $rel")
    }

    /** 主源码中含指定片段的 .kt 文件名。 */
    fun mainFilesContaining(needle: String): List<String> =
        mainKotlinSources.filter { it.readText().contains(needle) }.map { it.name }
}
