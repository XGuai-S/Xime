package com.kingzcheung.xime.model

import com.kingzcheung.xime.SymlinkSupport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelPathsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `nested names keep valid Unicode spaces and consecutive dots`() {
        val root = temp.newFolder()
        assertEquals(root.resolve("嵌套/模型 a..bin").canonicalFile, ModelPaths.child(root, "嵌套/模型 a..bin"))
    }

    @Test fun `unsafe names are rejected instead of normalized`() {
        val root = temp.newFolder()
        for (name in listOf("../a", "a/../b", "./a", "/absolute", "a\\b", "C:a", "a//b", "a/", "a\u0000b")) {
            assertTrue("must reject $name", runCatching { ModelPaths.child(root, name) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `broken links and intermediate regular files are rejected`() {
        SymlinkSupport.assumeAvailable()
        val root = temp.newFolder()
        SymlinkSupport.create(root.resolve("broken"), root.resolve("absent"))
        assertTrue(runCatching { ModelPaths.child(root, "broken/model.bin") }.isFailure)
        root.resolve("file").writeText("old")
        assertTrue(runCatching { ModelPaths.child(root, "file/model.bin") }.isFailure)
    }

    @Test fun `archive search ignores directory links and finds safe nested file`() {
        SymlinkSupport.assumeAvailable()
        val root = temp.newFolder()
        val outside = temp.newFolder().apply { resolve("model.bin").writeText("outside") }
        SymlinkSupport.create(root.resolve("link"), outside)
        root.resolve("unsafe").mkdir()
        SymlinkSupport.create(root.resolve("unsafe/model.bin"), outside.resolve("model.bin"))
        assertNull(ModelPaths.findFile(root, "model.bin"))
        root.resolve("nested").mkdir()
        val valid = root.resolve("nested/model.bin").apply { writeText("valid") }
        assertEquals(valid.canonicalFile, ModelPaths.findFile(root, "model.bin"))
    }
}
