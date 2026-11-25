package com.kingzcheung.xime.clipboard

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.clipboard.db.ClipboardDao
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import com.kingzcheung.xime.service.otp.OtpCollector
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OtpClipboardPrivacyTest {
    private lateinit var manager: ClipboardManager
    private lateinit var dao: ClipboardDao

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = ClipboardManager.getInstance(context)
        dao = ClipboardDatabase.getInstance(context).clipboardDao()
        dao.deleteAll()
        awaitCondition { manager.clipboardItems.value.isEmpty() }
    }

    @After
    fun tearDown() = runBlocking { dao.deleteAll() }

    @Test
    fun expiryRemovesOtpWithoutTouchingSameTextHistory() = runBlocking {
        val now = System.currentTimeMillis()
        val code = "731592"
        val normalId = dao.upsertAndTrim(code, now, maxItems = 1)
        val capturedAt = now - OtpCollector.FRESH_WINDOW_MS + 4_000
        val otpId = dao.upsertOtp(code, capturedAt)
        assertNotEquals(normalId, otpId)
        assertEquals(capturedAt, dao.findOtpByText(code)?.timestamp)
        dao.revive(otpId, now)
        dao.setPinned(otpId, true)
        dao.addQuickSend(otpId, now, 10)
        assertEquals(capturedAt, dao.findOtpByText(code)?.timestamp)
        assertFalse(dao.findOtpByText(code)!!.isPinned)
        assertNull(dao.findQuickSendByText(code))
        awaitCondition { manager.clipboardItems.value.any { it.id == otpId } }
        awaitCondition { dao.findOtpByText(code) == null }
        awaitCondition { manager.clipboardItems.value.none { it.id == otpId } }
        assertEquals(normalId, dao.findByText(code)?.id)
    }

    @Test
    fun notificationOtpDoesNotEmitClipboardChange() = runBlocking {
        val emitted = mutableListOf<ClipboardItem>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.clipboardChanged.collect { emitted.add(it) }
        }
        try {
            val code = "429617"
            val now = System.currentTimeMillis()
            manager.addOtp(code, now)
            awaitCondition { manager.clipboardItems.value.any { it.isOtp && it.text == code } }
            assertEquals(now, dao.findOtpByText(code)?.timestamp)
            assertNull(dao.findByText(code))
            // 相同数字作为普通复制仍能入库并发出变更事件。
            manager.addItem(code)
            awaitCondition { emitted.any { it.text == code && !it.isOtp } }
            assertFalse(emitted.any { it.isOtp })
            assertTrue(dao.findByText(code) != null)
        } finally {
            collector.cancelAndJoin()
        }
    }

    private suspend fun awaitCondition(predicate: suspend () -> Boolean) {
        withTimeout(8_000L) {
            while (!predicate()) delay(20)
        }
    }
}
