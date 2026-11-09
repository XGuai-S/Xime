package com.kingzcheung.xime

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class ComposeTestRule : TestWatcher() {
    
    val composeTestRule = createEmptyComposeRule()
    
    override fun finished(description: Description?) {
        super.finished(description)
    }
}