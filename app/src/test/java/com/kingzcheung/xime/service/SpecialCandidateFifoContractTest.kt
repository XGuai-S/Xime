package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialCandidateFifoContractTest {
    private val source = RepoFiles.mainKotlinSources
        .single { it.name == "ImeKeyRouter.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val selectBlock = source.substringAfter("internal fun selectCandidate(index: Int)")
        .substringBefore("internal fun selectCandidateGlobal(")

    @Test
    fun `calculator and capital amount picks use the key FIFO`() {
        assertTrue(selectBlock.contains("postEditorRimeJobWithSession"))
        assertFalse(
            "special candidate picks must not launch outside keyJobs FIFO",
            selectBlock.contains("service.serviceScope.launch(Dispatchers.Main + editorJob)"),
        )
    }

    @Test
    fun `calculator is cleared only inside the queued selection job`() {
        val queue = selectBlock.indexOf("postEditorRimeJobWithSession")
        val clear = selectBlock.indexOf("service.calculatorEngine.clear()")
        assertTrue("calculator clear must remain queued with the selected candidate", queue >= 0 && clear > queue)
    }
}
