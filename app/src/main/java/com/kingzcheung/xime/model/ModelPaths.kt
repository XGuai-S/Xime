package com.kingzcheung.xime.model

import java.io.File
import java.nio.file.Files

internal object ModelPaths {
    fun isSafeId(id: String): Boolean =
        id.isNotBlank() && id == id.trim() && id != "." && id != ".." &&
            id.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }

    fun isSafeRelativePath(name: String): Boolean =
        name.isNotBlank() && !name.startsWith('/') &&
            name.none { it == '\\' || it == ':' || it.isISOControl() } &&
            name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    /** The root may use an Android mount alias; descendants must not redirect through links. */
    fun child(root: File, name: String): File {
        require(isSafeRelativePath(name)) { "模型文件路径不安全" }
        val canonicalRoot = root.canonicalFile
        var current = canonicalRoot
        val parts = name.split('/')
        for ((index, part) in parts.withIndex()) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "模型路径不允许符号链接" }
            if (index < parts.lastIndex && current.exists()) {
                require(current.isDirectory) { "模型路径父级不是目录" }
            }
        }
        val resolved = current.canonicalFile
        require(resolved != canonicalRoot && resolved.toPath().startsWith(canonicalRoot.toPath())) {
            "模型路径超出目录"
        }
        return resolved
    }

    fun findFile(root: File, name: String): File? {
        require(isSafeRelativePath(name)) { "模型文件路径不安全" }
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) return null
        val direct = child(root, name)
        if (direct.isFile) return direct
        for (directory in root.listFiles().orEmpty()) {
            if (!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory) {
                runCatching { findFile(directory, name) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    fun validateFiles(files: List<ModelFile>) {
        require(files.all { isSafeRelativePath(it.name) }) { "模型清单包含不安全文件路径" }
        val names = files.map { it.name }
        require(names.toSet().size == names.size) { "模型清单存在重复路径" }
        val paths = names.toSet()
        for (name in names) {
            val parts = name.split('/')
            require((1 until parts.size).none { parts.take(it).joinToString("/") in paths }) {
                "模型文件和目录路径冲突"
            }
        }
    }
}
