package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.rime.RimeConfigHelper
import org.junit.Assert.*
import org.junit.Test

class CandidatePageSizeSettingsTest {
    @Test
    fun `page size slider covers every integer from five to fifty without changing default`() {
        assertEquals(5, SettingsPreferences.MIN_PAGE_SIZE)
        assertEquals(50, SettingsPreferences.MAX_PAGE_SIZE)
        assertEquals(20, SettingsPreferences.DEFAULT_PAGE_SIZE)
        for (value in 5..50) {
            assertEquals(value.toFloat(), SettingsPreferences.pageSizeSliderValue(value), 0f)
        }
    }

    @Test
    fun `legacy zero uses default and out of range imports do not break slider bounds`() {
        assertEquals(20f, SettingsPreferences.pageSizeSliderValue(0), 0f)
        assertEquals(5f, SettingsPreferences.pageSizeSliderValue(1), 0f)
        assertEquals(5f, SettingsPreferences.pageSizeSliderValue(Int.MIN_VALUE), 0f)
        assertEquals(50f, SettingsPreferences.pageSizeSliderValue(Int.MAX_VALUE), 0f)
    }

    @Test
    fun `five is retained in rime default patch instead of raised to twenty`() {
        val original = "patch:\n  menu:\n    page_size: 20\n  schema_list:\n    - schema: XGuai_flypy\n"
        val patched = RimeConfigHelper.patchDefaultCustomContent(original, 5)!!
        assertTrue(patched.contains("page_size: 5"))
        assertFalse(patched.contains("page_size: 20"))
        assertTrue(patched.contains("- schema: XGuai_flypy"))
        assertNull(RimeConfigHelper.patchDefaultCustomContent(patched, 5))
    }

    @Test
    fun `settings slider uses shared bounds and saves the selected integer`() {
        val source = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/ui/settings/LayoutDisplaySettingsScreen.kt",
        ).readText().substringAfter("val pageSizePref = SettingsPreferences.getPageSize(context)")
            .substringBefore("修改后需到方案设置中点击部署才能生效")
        assertTrue(source.contains("SettingsPreferences.pageSizeSliderValue(pageSizePref)"))
        assertTrue(source.contains("valueRange = SettingsPreferences.MIN_PAGE_SIZE.toFloat()..SettingsPreferences.MAX_PAGE_SIZE.toFloat()"))
        assertTrue(source.contains("steps = SettingsPreferences.MAX_PAGE_SIZE - SettingsPreferences.MIN_PAGE_SIZE - 1"))
        assertTrue(source.contains("SettingsPreferences.setPageSize(context, intValue)"))
        assertFalse(source.contains("valueRange = 20f..50f"))
    }
}
