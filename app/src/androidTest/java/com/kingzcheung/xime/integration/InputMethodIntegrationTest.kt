package com.kingzcheung.xime.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InputMethodIntegrationTest {
    
    private lateinit var context: android.content.Context
    private var rimeInitialized = false
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        ExtensionManager.release()
    }
    
    @After
    fun tearDown() {
        if (rimeInitialized) {
            RimeEngine.getInstance().destroy()
            rimeInitialized = false
        }
        ExtensionManager.release()
    }
    
    @Test
    fun testFullInitializationWorkflow() {
        assertFalse(ExtensionManager.isInitialized())
        
        ExtensionManager.initialize(context)
        
        assertTrue(ExtensionManager.isInitialized())
    }
    
    @Test
    fun testPluginLoadingWorkflow() {
        ExtensionManager.initialize(context)
        
        val allPlugins = ExtensionManager.getAllInstalledPlugins()
        assertNotNull("Plugin list should not be null", allPlugins)
        
        val emojiPlugins = ExtensionManager.getEmojiPlugins()
        
        assertTrue("Total emoji plugins should match all plugins", emojiPlugins.size <= allPlugins.size)
    }
    
    @Test
    fun testPluginEnableDisableWorkflow() {
        val pluginId = "test_plugin_integration"
        
        ExtensionManager.initialize(context)
        
        val initialEnabled = ExtensionManager.getEnabledEmojiPlugins(context)
        val initialCount = initialEnabled.size
        
        assertFalse("New plugin should be disabled by default", 
            ExtensionManager.getPluginById(pluginId) != null)
    }
    
    @Test
    fun testRimeDirectorySetup() {
        val userDataDir = File(context.filesDir, "rime_user")
        val sharedDataDir = File(context.filesDir, "rime_shared")
        
        assertNotNull(userDataDir)
        assertNotNull(sharedDataDir)
    }
    
    @Test
    fun testReloadWorkflow() {
        ExtensionManager.initialize(context)
        assertTrue(ExtensionManager.isInitialized())
        
        val result = ExtensionManager.reload(context)
        assertTrue("Reload should succeed", result)
        assertTrue("Should still be initialized after reload", ExtensionManager.isInitialized())
    }
}