package com.kingzcheung.xime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.CandidateItem
import com.kingzcheung.xime.ui.keyboard.KeyButton
import com.kingzcheung.xime.ui.keyboard.SpaceKeyButton
import com.kingzcheung.xime.ui.theme.DividerColor
import com.kingzcheung.xime.ui.theme.KeyBackground
import com.kingzcheung.xime.ui.theme.KeyTextColor
import com.kingzcheung.xime.ui.theme.KeyboardBackground
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardViewTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun keyButtonDisplaysText() {
        composeTestRule.setContent {
            KeyButton(
                text = "Q",
                onClick = {},
                backgroundColor = KeyBackground,
                textColor = KeyTextColor
            )
        }
        
        composeTestRule.onNodeWithText("Q").assertIsDisplayed()
    }
    
    @Test
    fun keyButtonHandlesClick() {
        var clicked = false
        composeTestRule.setContent {
            KeyButton(
                text = "A",
                onClick = { clicked = true },
                backgroundColor = KeyBackground,
                textColor = KeyTextColor
            )
        }
        
        composeTestRule.onNodeWithText("A").performClick()
        
        assert(clicked)
    }
    
    @Test
    fun spaceKeyButtonDisplaysCorrectly() {
        composeTestRule.setContent {
            SpaceKeyButton(
                onClick = {},
                backgroundColor = KeyBackground,
                textColor = KeyTextColor,
                schemaName = "en"
            )
        }
    }
    
    @Test
    fun candidateBarDisplaysCandidates() {
        composeTestRule.setContent {
            CandidateBar(
                state = CandidateBarState.ChineseCandidates(
                    candidates = listOf("你好", "世界", "测试"),
                    inputText = "nihao",
                ),
                visuals = CandidateBarVisuals(
                    backgroundColor = KeyboardBackground,
                    textColor = KeyTextColor,
                    dividerColor = DividerColor
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = {}
                )
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
        composeTestRule.onNodeWithText("世界").assertIsDisplayed()
    }
    
    @Test
    fun candidateItemDisplaysTextAndComment() {
        composeTestRule.setContent {
            CandidateItem(
                text = "你好",
                index = 0,
                onClick = {},
                textColor = KeyTextColor,
                comment = "wubi"
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
    }
}