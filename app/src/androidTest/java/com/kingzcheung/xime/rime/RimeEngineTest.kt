package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RimeEngineTest {
    
    private lateinit var context: android.content.Context
    private var rimeInitialized = false
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }
    
    @After
    fun tearDown() {
        if (rimeInitialized) {
            RimeEngine.getInstance().destroy()
            rimeInitialized = false
        }
    }
    
    @Test
    fun getInstanceReturnsSingleton() {
        val instance1 = RimeEngine.getInstance()
        val instance2 = RimeEngine.getInstance()
        
        assertSame(instance1, instance2)
    }
    
    @Test
    fun isInitializedReturnsFalseBeforeInit() {
        val engine = RimeEngine.getInstance()
        assertFalse(RimeEngine.isInitialized())
    }
    
    @Test
    fun processKeyReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.processKey(65, 0)
        assertFalse(result)
    }
    
    @Test
    fun getCandidatesReturnsEmptyArrayWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val candidates = engine.getCandidates()
        assertTrue(candidates.isEmpty())
    }
    
    @Test
    fun getInputReturnsEmptyStringWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val input = engine.getInput()
        assertEquals("", input)
    }
    
    @Test
    fun initializeCreatesValidDirectories() {
        val userDataDir = File(context.filesDir, "rime_user_test")
        val sharedDataDir = File(context.filesDir, "rime_shared_test")
        
        assertNotNull(userDataDir)
        assertNotNull(sharedDataDir)
    }
    
    @Test
    fun toggleAsciiModeReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.toggleAsciiMode()
        assertFalse(result)
    }
    
    @Test
    fun isAsciiModeReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.isAsciiMode()
        assertFalse(result)
    }
    
    @Test
    fun switchSchemaReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.switchSchema("wubi86")
        assertFalse(result)
    }
    
    @Test
    fun getAvailableSchemasReturnsEmptyArrayWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val schemas = engine.getAvailableSchemas()
        assertTrue(schemas.isEmpty())
    }
    
    @Test
    fun selectCandidateReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.selectCandidate(0)
        assertFalse(result)
    }
    
    @Test
    fun commitReturnsEmptyStringWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.commit()
        assertEquals("", result)
    }
    
    @Test
    fun deployReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.deploy()
        assertFalse(result)
    }
    
    @Test
    fun lookupTextReturnsEmptyStringWhenNotInitialized() {
        val engine = RimeEngine.getInstance()
        val result = engine.lookupText("工")
        assertEquals("", result)
    }
}