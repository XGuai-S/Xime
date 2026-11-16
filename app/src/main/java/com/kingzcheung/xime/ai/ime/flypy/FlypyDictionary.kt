package com.kingzcheung.xime.ai.ime.flypy

import android.content.Context
import android.content.res.AssetManager
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

data class FlypyCandidate(
    val text: String,
    val codeHint: String = ""
)

data class FlypyReverseEntry(
    val code: String,
    val charText: String
)

/**
 * High-speed in-memory dictionary for Xiaohe Yinxing (小鹤音形 / Flypy),
 * built from the user's active Rime/Flypy code tables (`flypy_dict.gz` + `flypy_dz.gz`).
 */
class FlypyDictionary internal constructor(
    private val codeMap: Map<String, List<String>>,
    private val osExtendedEntries: List<Pair<String, List<String>>>,
    private val reverseEntries: List<FlypyReverseEntry>
) {

    val isLoaded: Boolean
        get() = codeMap.isNotEmpty()

    val codeCount: Int
        get() = codeMap.size

    val reverseCharCount: Int
        get() = reverseEntries.size

    fun lookupExact(code: String): List<FlypyCandidate> {
        val words = codeMap[code] ?: return emptyList()
        return words.map { FlypyCandidate(text = it) }
    }

    fun hasExtendedOsContinuation(code: String): Boolean {
        if (!code.startsWith("os") || code.length < 3) return false
        return osExtendedEntries.any { (extCode, _) ->
            extCode.length > code.length && extCode.startsWith(code)
        }
    }

    fun lookupWithExtendedOs(code: String, maxPrefixHints: Int = 24): List<FlypyCandidate> {
        val exact = lookupExact(code)
        if (!code.startsWith("os") || code.length < 3) {
            return exact
        }
        val out = ArrayList<FlypyCandidate>(exact.size + 16)
        val seen = HashSet<String>(exact.size + 16)
        for (cand in exact) {
            if (seen.add(cand.text)) {
                out.add(cand)
            }
        }
        var addedPrefix = 0
        for ((extCode, words) in osExtendedEntries) {
            if (extCode.length > code.length && extCode.startsWith(code)) {
                val suffix = extCode.substring(code.length)
                for (w in words) {
                    if (seen.add(w)) {
                        out.add(FlypyCandidate(text = w, codeHint = suffix))
                        addedPrefix++
                        if (addedPrefix >= maxPrefixHints) return out
                    }
                }
            }
        }
        return out
    }

    /**
     * Bidirectional wildcard reverse lookup (`知音查形 / 知形查音`) backed by `flypydz` 4-code single chars.
     *
     * Supported patterns with `` ` `` wildcard:
     * - `ji\`` or `ji\`\`` -> 知音查形: matches `ji..` (`瘠〔jibo〕`)
     * - `\`bo` or `\`\`bo` -> 知形查音: matches `..bo` (`瘠〔jibo〕`) or `\`al` -> `丁〔dkal〕`
     * - `j\`bo` -> positional 4-slot wildcard (`j.bo`)
     * - `j\`b` -> initial `j` + 1st shape `b` (`j.b.`)
     */
    fun queryWildcard(pattern: String, maxResults: Int = 300): List<FlypyCandidate> {
        if (pattern.isEmpty() || !pattern.contains('`') || reverseEntries.isEmpty()) {
            return emptyList()
        }
        if (pattern.none { it in 'a'..'z' }) {
            return emptyList()
        }

        val primary = ArrayList<FlypyCandidate>(minOf(maxResults, 64))
        val secondary = ArrayList<FlypyCandidate>(32)
        val seenPairs = HashSet<String>(maxResults)

        fun appendTarget(list: MutableList<FlypyCandidate>, entry: FlypyReverseEntry) {
            val key = "${entry.charText}\t${entry.code}"
            if (seenPairs.add(key)) {
                list.add(FlypyCandidate(text = entry.charText, codeHint = entry.code))
            }
        }

        when {
            // 1. Explicit 4-slot positional wildcard (`ji`` `, ```hg`, `j`xp`, `a`b` `)
            pattern.length == 4 -> {
                for (entry in reverseEntries) {
                    val c = entry.code
                    if (
                        (pattern[0] == '`' || pattern[0] == c[0]) &&
                        (pattern[1] == '`' || pattern[1] == c[1]) &&
                        (pattern[2] == '`' || pattern[2] == c[2]) &&
                        (pattern[3] == '`' || pattern[3] == c[3])
                    ) {
                        appendTarget(primary, entry)
                        if (primary.size >= maxResults) break
                    }
                }
            }

            // 2. Leading backtick (`知形查音`: `` `hg ``, `` `h ``, `` ``h ``)
            pattern.startsWith("`") -> {
                val letters = pattern.trimStart('`')
                val leadingTicks = pattern.length - letters.length
                when {
                    // e.g. ``h -> slot 2 is 'h'
                    leadingTicks == 2 && letters.length == 1 && letters[0] in 'a'..'z' -> {
                        val ch = letters[0]
                        for (entry in reverseEntries) {
                            if (entry.code[2] == ch) {
                                appendTarget(primary, entry)
                                if (primary.size >= maxResults) break
                            }
                        }
                    }

                    // e.g. `hg -> prioritize exact 2-char shape suffix `..hg`
                    letters.length == 2 && letters.all { it in 'a'..'z' } -> {
                        val s0 = letters[0]
                        val s1 = letters[1]
                        for (entry in reverseEntries) {
                            val c = entry.code
                            if (c[2] == s0 && c[3] == s1) {
                                appendTarget(primary, entry)
                                if (primary.size >= maxResults) break
                            } else if (c[1] == s0 && c[2] == s1 && secondary.size < maxResults) {
                                appendTarget(secondary, entry)
                            }
                        }
                    }

                    // e.g. `h -> prioritize 1st shape stroke `c[2] == 'h'`, then 2nd shape stroke `c[3] == 'h'`
                    letters.length == 1 && letters[0] in 'a'..'z' -> {
                        val ch = letters[0]
                        for (entry in reverseEntries) {
                            val c = entry.code
                            if (c[2] == ch) {
                                appendTarget(primary, entry)
                                if (primary.size >= maxResults) break
                            } else if (c[3] == ch && secondary.size < maxResults) {
                                appendTarget(secondary, entry)
                            }
                        }
                    }

                    // e.g. `bya or mixed pattern with internal backtick
                    else -> {
                        val regex = buildWildcardRegex(pattern)
                        for (entry in reverseEntries) {
                            if (regex.matches(entry.code)) {
                                appendTarget(primary, entry)
                                if (primary.size >= maxResults) break
                            }
                        }
                    }
                }
            }

            // 3. Trailing backtick (`知音查形`: `ji\``, `j\``, `jib\``)
            pattern.endsWith("`") && pattern.dropLastWhile { it == '`' }.all { it in 'a'..'z' } -> {
                val prefix = pattern.dropLastWhile { it == '`' }
                for (entry in reverseEntries) {
                    if (entry.code.startsWith(prefix)) {
                        appendTarget(primary, entry)
                        if (primary.size >= maxResults) break
                    }
                }
            }

            // 4. Middle backtick (`j`h` -> initial `j` + 1st shape `h`, or general regex)
            pattern.length == 3 && pattern[0] in 'a'..'z' && pattern[1] == '`' && pattern[2] in 'a'..'z' -> {
                val initCh = pattern[0]
                val shapeCh = pattern[2]
                for (entry in reverseEntries) {
                    val c = entry.code
                    if (c[0] == initCh && c[2] == shapeCh) {
                        appendTarget(primary, entry)
                        if (primary.size >= maxResults) break
                    } else if (c[0] == initCh && c[3] == shapeCh && secondary.size < maxResults) {
                        appendTarget(secondary, entry)
                    }
                }
            }

            else -> {
                val regex = buildWildcardRegex(pattern)
                for (entry in reverseEntries) {
                    if (regex.matches(entry.code)) {
                        appendTarget(primary, entry)
                        if (primary.size >= maxResults) break
                    }
                }
            }
        }

        if (primary.size >= maxResults || secondary.isEmpty()) {
            return primary.take(maxResults)
        }
        val combined = ArrayList<FlypyCandidate>(minOf(maxResults, primary.size + secondary.size))
        combined.addAll(primary)
        for (item in secondary) {
            if (combined.size >= maxResults) break
            combined.add(item)
        }
        return combined
    }

    private fun buildWildcardRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var idx = 0
        while (idx < pattern.length) {
            val ch = pattern[idx]
            if (ch == '`') {
                while (idx + 1 < pattern.length && pattern[idx + 1] == '`') {
                    idx++
                }
                sb.append("[a-z]+")
            } else if (ch in 'a'..'z') {
                sb.append(ch)
            }
            idx++
        }
        if (!pattern.endsWith('`')) {
            sb.append("[a-z]*")
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    companion object {
        const val ASSET_MAIN_DICT = "flypy_dict.tsv"
        const val ASSET_REVERSE_DZ = "flypy_dz.tsv"

        const val CUSTOM_DICT_FILENAME = "custom_rime_dict.tsv"
        const val CUSTOM_DZ_FILENAME = "custom_rime_dz.tsv"

        fun getCustomDictFile(context: Context): File = File(context.filesDir, CUSTOM_DICT_FILENAME)
        fun getCustomDzFile(context: Context): File = File(context.filesDir, CUSTOM_DZ_FILENAME)

        fun isCustomDictionaryActive(context: Context): Boolean {
            val f = getCustomDictFile(context)
            return f.exists() && f.length() > 0
        }

        fun load(context: Context): FlypyDictionary {
            val customDict = getCustomDictFile(context)
            val customDz = getCustomDzFile(context)
            if (customDict.exists() && customDict.length() > 0) {
                try {
                    val dictIn = FileInputStream(customDict)
                    val dzIn = if (customDz.exists() && customDz.length() > 0) {
                        FileInputStream(customDz)
                    } else {
                        openAssetWithFallback(context.assets, listOf(ASSET_REVERSE_DZ, "flypy_dz", "flypy_dz.gz"))
                    }
                    return dictIn.use { dIn ->
                        dzIn.use { zIn ->
                            loadFromStreams(dIn, zIn)
                        }
                    }
                } catch (e: Exception) {
                    // Fall back to built-in assets on reading failure
                }
            }
            return loadFromAssets(context)
        }

        fun importCustomDictionary(
            context: Context,
            dictStream: InputStream,
            dzStream: InputStream? = null
        ): FlypyDictionary {
            val customDict = getCustomDictFile(context)
            val customDz = getCustomDzFile(context)
            val tmpDict = File(context.cacheDir, "tmp_custom_dict.tsv")
            val tmpDz = File(context.cacheDir, "tmp_custom_dz.tsv")

            tmpDict.outputStream().use { out -> dictStream.copyTo(out) }
            if (dzStream != null) {
                tmpDz.outputStream().use { out -> dzStream.copyTo(out) }
            } else if (tmpDz.exists()) {
                tmpDz.delete()
            }

            val loaded = FileInputStream(tmpDict).use { dIn ->
                if (tmpDz.exists() && tmpDz.length() > 0) {
                    FileInputStream(tmpDz).use { zIn -> loadFromStreams(dIn, zIn) }
                } else {
                    openAssetWithFallback(context.assets, listOf(ASSET_REVERSE_DZ, "flypy_dz", "flypy_dz.gz")).use { zIn ->
                        loadFromStreams(dIn, zIn)
                    }
                }
            }

            if (!loaded.isLoaded || loaded.codeCount == 0) {
                tmpDict.delete()
                tmpDz.delete()
                throw IllegalArgumentException("解析码表失败或码表为空")
            }

            if (!tmpDict.renameTo(customDict)) {
                tmpDict.copyTo(customDict, overwrite = true)
                tmpDict.delete()
            }
            if (tmpDz.exists() && tmpDz.length() > 0) {
                if (!tmpDz.renameTo(customDz)) {
                    tmpDz.copyTo(customDz, overwrite = true)
                    tmpDz.delete()
                }
            } else if (customDz.exists()) {
                customDz.delete()
            }

            return loaded
        }

        fun restoreDefaultDictionary(context: Context): FlypyDictionary {
            val customDict = getCustomDictFile(context)
            val customDz = getCustomDzFile(context)
            if (customDict.exists()) customDict.delete()
            if (customDz.exists()) customDz.delete()
            return loadFromAssets(context)
        }

        fun empty(): FlypyDictionary = FlypyDictionary(
            codeMap = emptyMap(),
            osExtendedEntries = emptyList(),
            reverseEntries = emptyList()
        )

        private fun openAssetWithFallback(
            assets: AssetManager,
            candidates: List<String>
        ): InputStream {
            var lastError: Exception? = null
            for (name in candidates) {
                try {
                    return assets.open(name)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: FileNotFoundException("Missing Flypy asset: $candidates")
        }

        /**
         * Auto-detects GZIP magic header (`0x1f 0x8b`) so both plain UTF-8 `.tsv` assets
         * (preferred on Android because AAPT2 strips `.gz` extensions!) and `.gz` streams work seamlessly.
         */
        private fun unwrapIfGzip(rawStream: InputStream, bufferSize: Int): InputStream {
            val buffered = if (rawStream is BufferedInputStream) {
                rawStream
            } else {
                BufferedInputStream(rawStream, bufferSize)
            }
            buffered.mark(4)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            return if (b0 == 0x1f && b1 == 0x8b) {
                GZIPInputStream(buffered, bufferSize)
            } else {
                buffered
            }
        }

        fun loadFromAssets(context: Context): FlypyDictionary {
            val assets = context.assets
            return openAssetWithFallback(
                assets,
                listOf(ASSET_MAIN_DICT, "flypy_dict", "flypy_dict.gz")
            ).use { dictIn ->
                openAssetWithFallback(
                    assets,
                    listOf(ASSET_REVERSE_DZ, "flypy_dz", "flypy_dz.gz")
                ).use { dzIn ->
                    loadFromStreams(dictIn, dzIn)
                }
            }
        }

        fun loadFromStreams(
            dictStream: InputStream,
            dzStream: InputStream
        ): FlypyDictionary {
            val map = HashMap<String, List<String>>(165_000, 0.8f)
            val osExt = ArrayList<Pair<String, List<String>>>(512)

            BufferedReader(
                InputStreamReader(unwrapIfGzip(dictStream, 32 * 1024), StandardCharsets.UTF_8),
                32 * 1024
            ).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val firstTab = line.indexOf('\t')
                    if (firstTab > 0 && firstTab < line.length - 1) {
                        val code = line.substring(0, firstTab)
                        val secondTab = line.indexOf('\t', firstTab + 1)
                        val words: List<String> = if (secondTab < 0) {
                            listOf(line.substring(firstTab + 1))
                        } else {
                            val list = ArrayList<String>(4)
                            var start = firstTab + 1
                            var tab = secondTab
                            while (tab >= 0) {
                                if (tab > start) {
                                    list.add(line.substring(start, tab))
                                }
                                start = tab + 1
                                tab = line.indexOf('\t', start)
                            }
                            if (start < line.length) {
                                list.add(line.substring(start))
                            }
                            list
                        }
                        if (words.isNotEmpty()) {
                            map[code] = words
                            if (code.length > 4 && code.startsWith("os")) {
                                osExt.add(code to words)
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }

            val reverseList = ArrayList<FlypyReverseEntry>(9_500)
            BufferedReader(
                InputStreamReader(unwrapIfGzip(dzStream, 16 * 1024), StandardCharsets.UTF_8),
                16 * 1024
            ).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val tab = line.indexOf('\t')
                    if (tab == 4 && tab < line.length - 1) {
                        val code = line.substring(0, 4)
                        val ch = line.substring(5)
                        if (ch.isNotEmpty()) {
                            reverseList.add(FlypyReverseEntry(code = code, charText = ch))
                        }
                    }
                    line = reader.readLine()
                }
            }

            return FlypyDictionary(
                codeMap = map,
                osExtendedEntries = osExt,
                reverseEntries = reverseList
            )
        }
    }
}
