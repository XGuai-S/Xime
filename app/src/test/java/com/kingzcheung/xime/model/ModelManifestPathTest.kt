package com.kingzcheung.xime.model

import com.kingzcheung.xime.SymlinkSupport
import android.content.Context
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ModelManifestPathTest {
    @get:Rule val temp = TemporaryFolder()

    private fun context(root: File): Context = mock<Context>().also {
        whenever(it.filesDir).thenReturn(root)
        whenever(it.cacheDir).thenReturn(temp.newFolder())
        val prefs = mock<android.content.SharedPreferences>()
        val editor = mock<android.content.SharedPreferences.Editor>()
        whenever(it.getSharedPreferences(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.remove(org.mockito.kotlin.any())).thenReturn(editor)
    }

    private fun model(id: String = "test-model", names: List<String>) = ModelInfo(
        id, "Test", "", ModelCategory.PREDICTION,
        versions = listOf(ModelVersion(files = names.map { ModelFile(it, "") })),
    )

    @Test fun `model directory rejects unsafe identifiers`() {
        val ctx = context(temp.newFolder())
        for (id in listOf("../escape", "a/b", "a\\b", ".", "..", "C:escape", "")) {
            assertTrue("id must be rejected: $id", runCatching { ModelStorage.getModelDir(ctx, id) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `delete validates complete manifest before touching any file`() {
        val root = temp.newFolder()
        val ctx = context(root)
        val dir = root.resolve("models/test-model").apply { mkdirs() }
        val valid = dir.resolve("model.bin").apply { writeText("old-valid") }
        val outside = root.resolve("outside.bin").apply { writeText("outside-valid") }
        val result = ModelManager.deleteModel(ctx, model(names = listOf("model.bin", "../../outside.bin")))
        assertFalse(result)
        assertEquals("old-valid", valid.readText())
        assertEquals("outside-valid", outside.readText())
    }

    @Test fun `model directory rejects existing symlink to outside`() {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        root.resolve("models").mkdirs()
        SymlinkSupport.create(root.resolve("models/test-model"), outside)
        assertTrue(runCatching { ModelStorage.getModelDir(context(root), "test-model") }.isFailure)
    }

    @Test fun `delete refuses symlink file without touching target`() {
        val root = temp.newFolder()
        val outside = temp.newFile().apply { writeText("outside-valid") }
        val dir = root.resolve("models/test-model").apply { mkdirs() }
        SymlinkSupport.create(dir.resolve("model.bin"), outside)
        assertFalse(ModelManager.deleteModel(context(root), model(names = listOf("model.bin"))))
        assertEquals("outside-valid", outside.readText())
        assertTrue(Files.isSymbolicLink(dir.resolve("model.bin").toPath()))
    }

    @Test fun `index rejects unsafe model and complete unsafe version`() {
        val text = """
            models:
              - id: ../escape
                name: Escape
                versions:
                  - version: "1"
                    files:
                      - name: model.bin
                        url: ""
              - id: safe
                name: Safe
                versions:
                  - version: "bad"
                    files:
                      - name: model.bin
                        url: ""
                      - name: ../../outside.bin
                        url: ""
                  - version: "good"
                    files:
                      - name: nested/a..bin
                        url: ""
        """.trimIndent()
        val models = ModelIndexLoader.parseModelsIndex(text)
        assertEquals(listOf("safe"), models.map { it.id })
        assertEquals(listOf("good"), models.single().versions.map { it.version })
        assertEquals("nested/a..bin", models.single().files.single().name)
    }

    @Test fun `download rejects unsafe manifest before creating work or parsing URL`() = kotlinx.coroutines.runBlocking {
        val root = temp.newFolder()
        val states = mutableListOf<ModelDownloadState>()
        ModelDownloader.downloadModel(context(root), model(names = listOf("model.bin", "../../outside.bin")), { states += it })
        assertEquals(1, states.size)
        assertTrue(states.single() is ModelDownloadState.Error)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `download rejects unsafe model identifier without touching outside`() = kotlinx.coroutines.runBlocking {
        val root = temp.newFolder()
        val states = mutableListOf<ModelDownloadState>()
        ModelDownloader.downloadModel(context(root), model("../outside", listOf("model.bin")), { states += it })
        assertTrue(states.single() is ModelDownloadState.Error)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `download rejects file in place of model directory before work creation`() = kotlinx.coroutines.runBlocking {
        val root = temp.newFolder()
        root.resolve("models").mkdirs()
        val marker = root.resolve("models/test-model").apply { writeText("old-valid") }
        val states = mutableListOf<ModelDownloadState>()
        ModelDownloader.downloadModel(context(root), model(names = listOf("model.bin")), { states += it })
        assertTrue(states.single() is ModelDownloadState.Error)
        assertEquals("old-valid", marker.readText())
        assertEquals(listOf("test-model"), root.resolve("models").listFiles()!!.map { it.name })
    }

    @Test fun `models root cannot redirect through symlink`() {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        SymlinkSupport.create(root.resolve("models"), outside)
        val ctx = context(root)
        assertTrue(runCatching { ModelStorage.getModelsRoot(ctx) }.isFailure)
        assertTrue(runCatching { ModelStorage.getModelDir(ctx, "test-model") }.isFailure)
        assertFalse(ModelManager.deleteModel(ctx, model(names = listOf("model.bin"))))
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test fun `ordinary nested files remain downloadable detection targets`() {
        val root = temp.newFolder()
        root.resolve("models/test-model/nested").mkdirs()
        root.resolve("models/test-model/nested/a..bin").writeText("valid")
        assertTrue(ModelManager.isModelDownloaded(context(root), model(names = listOf("nested/a..bin"))))
    }
}
