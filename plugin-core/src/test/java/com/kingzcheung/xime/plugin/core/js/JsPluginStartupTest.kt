package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsPluginStartupTest {
    @get:Rule val temp = TemporaryFolder()

    private fun withPlugin(script: String, block: (JsPluginAdapter, PluginContext, JsScriptRuntime) -> Unit) {
        val dir = temp.newFolder()
        File(dir, "main.js").writeText(script)
        val id = "startup-${dir.name}"
        val runtime = JsScriptRuntime(id, dir, "main.js", NoopPluginConfigStore, callTimeoutMs = 300)
        val info = PluginInfo(id = id, name = "startup", description = "test", iconResId = 0,
            versionCode = 1, versionName = "1.0", path = File(dir, "main.js").absolutePath, type = "tool")
        val context = PluginContext(application = Application(), pluginInfo = info, configStore = NoopPluginConfigStore)
        try {
            block(JsPluginAdapter(runtime, context), context, runtime)
        } finally {
            runtime.close()
        }
    }

    private fun assertStartupFails(script: String) = withPlugin(script) { adapter, context, _ ->
        val failure = runCatching { adapter.onLoad(context) }.exceptionOrNull()
        assertTrue("failed initialization must not publish a usable adapter", failure is IllegalStateException)
    }

    @Test fun `synchronous onLoad exception fails adapter startup`() = assertStartupFails(
        "globalThis.plugin = { onLoad() { throw new Error('init failed'); } };"
    )

    @Test fun `rejected async onLoad fails adapter startup`() = assertStartupFails(
        "globalThis.plugin = { async onLoad() { throw new Error('async init failed'); } };"
    )

    @Test fun `onLoad execution timeout fails adapter startup`() = assertStartupFails(
        "globalThis.plugin = { onLoad() { while (true) {} } };"
    )

    @Test fun `missing optional onLoad remains successful`() = withPlugin(
        "globalThis.plugin = { healthy() { return true; } };"
    ) { adapter, context, runtime ->
        adapter.onLoad(context)
        assertEquals(true, runtime.call("healthy"))
    }

    @Test fun `null onLoad return remains successful`() = withPlugin(
        "globalThis.plugin = { onLoad() { return null; }, healthy() { return true; } };"
    ) { adapter, context, runtime ->
        adapter.onLoad(context)
        assertEquals(true, runtime.call("healthy"))
    }

    @Test fun `async onLoad settles before adapter is returned`() = withPlugin(
        "globalThis.plugin = { ready: false, async onLoad() { await Promise.resolve(); this.ready = true; }, healthy() { return this.ready; } };"
    ) { adapter, context, runtime ->
        adapter.onLoad(context)
        assertEquals(true, runtime.call("healthy"))
    }
}
