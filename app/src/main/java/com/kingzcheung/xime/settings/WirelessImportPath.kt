package com.kingzcheung.xime.settings

import java.io.File
import java.io.IOException

/** Resolve an existing or prospective descendant without interpreting absolute paths as relative. */
internal fun resolveWirelessImportPath(root: File, rawPath: String?): File? {
    if (rawPath.isNullOrBlank()) return null
    val normalized = rawPath.replace('\\', '/')
    if ('\u0000' in normalized || normalized.split('/').any { it == ".." }) return null
    return try {
        val base = root.canonicalFile.toPath()
        val input = File(normalized)
        val candidate = (if (input.isAbsolute) input else File(root, normalized)).canonicalFile
        val path = candidate.toPath()
        candidate.takeIf { path != base && path.startsWith(base) }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
