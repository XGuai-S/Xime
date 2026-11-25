package com.kingzcheung.xime.association

import android.content.Context

class NgramFusionEngine(appContext: Context) {
    companion object {
        // 模型与用户记录同时命中时，用户记录权重更高（个性化优先）
        private const val MERGED_MODEL_WEIGHT = 0.3f
        private const val USER_CANDIDATE_LIMIT = 10
    }

    private val userNgramCache = UserNgramCache(appContext)

    @Volatile
    private var isInitialized = false

    suspend fun initialize(): Boolean {
        if (isInitialized) return true

        val result = userNgramCache.initialize()
        isInitialized = result
        return result
    }

    fun recordUserInput(text: String) {
        userNgramCache.recordInput(text)
    }

    /**
     * 融合规则：
     * - 只有模型命中：用归一化后的模型分；
     * - 只有用户记录命中：用用户分；
     * - 两者都命中：MERGED_MODEL_WEIGHT * 模型分 + (1 - MERGED_MODEL_WEIGHT) * 用户分。
     * 用户记录为空时结果等价于按归一化模型分排序（旧实现多出的"无用户候选"分支
     * 只是把所有分数统一乘 0.7，排序不变，已合并）。
     */
    fun fuseCandidates(
        modelCandidates: List<AssociationCandidate>,
        contextText: String
    ): List<AssociationCandidate> {
        if (!isInitialized) {
            return modelCandidates
        }

        val scores = LinkedHashMap<String, Float>()
        modelCandidates.forEach { candidate ->
            scores[candidate.text] = normalizeModelScore(candidate.score)
        }

        userNgramCache.getUserCandidates(contextText, USER_CANDIDATE_LIMIT).forEach { (word, userScore) ->
            val modelScore = scores[word]
            scores[word] = if (modelScore != null) {
                MERGED_MODEL_WEIGHT * modelScore + (1 - MERGED_MODEL_WEIGHT) * userScore
            } else {
                userScore
            }
        }

        return scores.map { (word, score) -> AssociationCandidate(word, score) }
            .sortedByDescending { it.score }
    }

    private fun normalizeModelScore(score: Float): Float {
        return when {
            score > 0 -> (score / 100f).coerceIn(0f, 1f)
            score < 0 -> kotlin.math.exp((score / 10f).toDouble()).toFloat().coerceIn(0f, 1f)
            else -> 0.5f
        }
    }

    suspend fun saveCache() {
        userNgramCache.save()
    }

    fun clearCache() {
        userNgramCache.clear()
    }

    fun getCacheSize(): Int = userNgramCache.getCacheSize()

    fun isInitialized(): Boolean = isInitialized
}
