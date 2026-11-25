package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaManager

/**
 * 自动造词的「单字码基底」来源解析。
 *
 * ## 为什么需要它
 *
 * `xime.yaml` 的 `auto_word.char_dict` 默认写死 `Flypy_char`（小鹤音形单字表）。
 * 但用户可能启用别的方案（五笔、其它音形…），若始终用小鹤单字表造词，
 * 造出的编码在用户当前方案里**打不出来**——表现就是「有些词组不了」：
 * 造词库里有这个词，但按自己方案的码永远匹配不上。
 *
 * ## 解析顺序
 *
 * 1. `auto_word.char_dict_by_schema.<方案id>`：用户显式配了该方案的单字表 → 用它；
 * 2. `auto_word.char_dict`：全局配置（兼容旧行为，用户可能就是想全局共用一个表）；
 * 3. 都没有 → 从**当前方案主词典**里收集满四码单字（[SchemeCodeIndex] 会自行过滤）。
 *
 * 第 3 条是关键兜底：方案没配单字表时，用方案自己的词典当基底，
 * 至少能保证「造出的码在本方案打得出来」，不会引入别的方案的字码。
 */
internal object AutoWordCharDict {

    /**
     * 解析当前方案应使用的单字码表名。
     *
     * @param schemaId 当前启用方案（空串表示未知，退回全局配置）
     * @param config   自动造词配置
     * @param rootDict 当前方案主词典名（[SchemaManager.getReferencedDictName] 的结果）
     * @return 词典名（不含 `.dict.yaml`）；返回 `null` 表示应从主词典收集
     */
    fun resolveDictName(
        schemaId: String,
        config: com.kingzcheung.xime.settings.AutoWordConfig,
        rootDict: String?,
    ): String? {
        // 1) 方案专属配置优先
        if (schemaId.isNotEmpty()) {
            config.charDictBySchema[schemaId]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // 2) 全局配置（旧行为，保持兼容）。
        //    注意 AutoWordConfig.charDict 的默认值就是 "Flypy_char"，所以「用户没配」和
        //    「用户显式配了 Flypy_char」在这里无法区分——这正是要保留的行为：
        //    小鹤用户即使不配任何东西，也继续用小鹤单字表，与本修复前一致。
        val globalDict = config.charDict.takeIf { it.isNotBlank() }
        if (globalDict != null) {
            // 2a) 全局表就是当前方案适配的表（小鹤方案 + Flypy_char）→ 直接用
            if (isDictOfSchema(schemaId, globalDict)) return globalDict
            // 2b) 全局表属于**别的方案**（用户换到五笔但全局仍写 Flypy_char）→
            //     不能拿它给当前方案造词（那正是「造词串方案」的根因），
            //     改用当前方案主词典兜底。
            return rootDict?.takeIf { it.isNotBlank() }
        }
        // 3) 全局未配置：方案主词典兜底，让 SchemeCodeIndex 自行挑满四码单字
        return rootDict?.takeIf { it.isNotBlank() }
    }

    /**
     * 全局单字表是否属于 [schemaId] 这个方案。
     *
     * 判据：方案 id 与词典名的「语义前缀」对得上。小鹤系方案（`xime_flypy` / `XGuai_flypy`
     * / `flypy`）配 `Flypy_char`；其它方案（`wubi86` / `pinyin_simp`…）配 `Flypy_char`
     * 明显不匹配，应走主词典兜底。
     *
     * 这里刻意用**保守**的小写包含判断，不做复杂的方案元数据查询：
     * 判错的代价是「多走一次主词典兜底」（结果仍然可用于造词），
     * 而漏判的代价是「继续用错方案的字码」（正是要修的 bug）。
     */
    private fun isDictOfSchema(schemaId: String, dictName: String): Boolean {
        if (schemaId.isEmpty()) return true
        val schema = schemaId.lowercase()
        val dict = dictName.lowercase()
        // 小鹤系：flypy / xime_flypy / XGuai_flypy ↔ Flypy_char
        if (dict.contains("flypy")) {
            return schema.contains("flypy") || schema.contains("xiaohe")
        }
        // 五笔系
        if (dict.contains("wubi")) return schema.contains("wubi")
        // 拼音系
        if (dict.contains("pinyin")) return schema.contains("pinyin")
        // 无法归类：保守认为匹配（保持旧行为，不擅自改变用户配置的语义）
        return true
    }
}
