package com.kingzcheung.xime.association

import com.kingzcheung.xime.model.ModelRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 关闭智能联想 / 删除模型 / 服务销毁都走 AssociationManager.release()。
 * 模型被 keepWarm 标为常驻后，release 必须真正释放，不能依赖调用方先 releaseWarm。
 */
class AssociationManagerReleaseTest {
    companion object {
        private const val ID = "predictive_text"
        private val released = AtomicInteger()

        init {
            // register 对重复 id 跳过：本类只注册一次假模型，不触碰真实推理进程
            ModelRuntime.register(ID, loader = { true }, releaser = { released.incrementAndGet() })
        }
    }

    private val initializedField = AssociationManager::class.java
        .getDeclaredField("isInitialized")
        .apply { isAccessible = true }

    private fun setManagerInitialized(value: Boolean) = initializedField.setBoolean(null, value)

    @Before
    fun loadHotModel() {
        released.set(0)
        assertTrue(runBlocking { ModelRuntime.load(ID) })
        ModelRuntime.keepWarm(ID)
        setManagerInitialized(true)
    }

    @After
    fun reset() {
        setManagerInitialized(false)
        ModelRuntime.releaseWarm(ID)
        while (ModelRuntime.isLoaded(ID)) ModelRuntime.unload(ID)
    }

    @Test
    fun `release frees a hot model without caller clearing warm flag`() {
        AssociationManager.release()

        assertEquals(1, released.get())
        assertFalse(ModelRuntime.isLoaded(ID))
        assertFalse(AssociationManager.isInitialized())
    }

    @Test
    fun `release after caller already cleared warm flag frees exactly once`() {
        ModelRuntime.releaseWarm(ID)
        AssociationManager.release()
        AssociationManager.release()

        assertEquals(1, released.get())
        assertFalse(ModelRuntime.isLoaded(ID))
    }

    @Test
    fun `release frees model when manager initialized flag is stale`() {
        setManagerInitialized(false)

        AssociationManager.release()

        assertEquals(1, released.get())
        assertFalse(ModelRuntime.isLoaded(ID))
        assertFalse(AssociationManager.isInitialized())
    }

    @Test
    fun `plain unload still keeps a hot model`() {
        // 钉住前提：常驻模型单靠 unload 不会释放，release 必须先清常驻标记
        ModelRuntime.unload(ID)

        assertEquals(0, released.get())
        assertTrue(ModelRuntime.isLoaded(ID))
    }
}
