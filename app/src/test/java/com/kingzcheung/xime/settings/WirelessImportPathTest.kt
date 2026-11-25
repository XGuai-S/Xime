package com.kingzcheung.xime.settings

import com.kingzcheung.xime.SymlinkSupport
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WirelessImportPathTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun resolvesOnlyDescendants() {
        val root = folder.newFolder("files")
        val inside = File(root, "方案/test.yaml").canonicalFile
        assertEquals(inside, resolveWirelessImportPath(root, "方案/test.yaml"))
        assertEquals(inside, resolveWirelessImportPath(root, inside.path))
        assertNull(resolveWirelessImportPath(root, root.path))
        assertNull(resolveWirelessImportPath(root, "."))
        assertNull(resolveWirelessImportPath(root, "../files-other/test.yaml"))
        assertNull(resolveWirelessImportPath(root, "a\\..\\test.yaml"))
        assertNull(resolveWirelessImportPath(root, "a\u0000b"))
        val sibling = folder.newFolder("files-other")
        assertNull(resolveWirelessImportPath(root, File(sibling, "test.yaml").path))
    }

    @Test fun rejectsSymlinkEscapingToSibling() {
        val root = folder.newFolder("files")
        val sibling = folder.newFolder("files-other")
        File(sibling, "target.txt").writeText("fixture")
        SymlinkSupport.create(File(root, "link"), sibling)
        assertNull(resolveWirelessImportPath(root, "link/target.txt"))
    }
}
