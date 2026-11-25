package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelDownloadState
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadIdentityTest {
    private fun item(id: String) = ModelItemState(ModelInfo(id, id, "", ModelCategory.OTHER))

    @Test fun `progress follows model id after reordered refresh`() {
        val a = item("a")
        val b = item("b")
        val progress = ModelDownloadState.Downloading(0.5f, 5, 10)
        val result = updateModelItemsById(listOf(b, a), "a") { it.copy(downloadState = progress) }
        assertSame(b, result[0])
        assertEquals("a", result[1].model.id)
        assertEquals(progress, result[1].downloadState)
    }

    @Test fun `removed model callback cannot alter replacement at same index`() {
        val items = listOf(item("b"))
        var called = false
        val result = updateModelItemsById(items, "a") { called = true; it.copy(isDownloaded = true) }
        assertSame(items, result)
        assertFalse(called)
        assertFalse(result.single().isDownloaded)
    }
}
