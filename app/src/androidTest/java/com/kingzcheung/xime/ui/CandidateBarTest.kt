package com.kingzcheung.xime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.CandidateItem
import com.kingzcheung.xime.ui.theme.DividerColor
import com.kingzcheung.xime.ui.theme.KeyTextColor
import com.kingzcheung.xime.ui.theme.KeyboardBackground
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidateBarTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
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
        composeTestRule.onNodeWithText("测试").assertIsDisplayed()
    }
    
    @Test
    fun candidateBarHandlesEmptyCandidates() {
        composeTestRule.setContent {
            CandidateBar(
                state = CandidateBarState.Idle,
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
    }
    
    @Test
    fun candidateBarDisplaysInputTextWhenComposing() {
        composeTestRule.setContent {
            CandidateBar(
                state = CandidateBarState.ChineseCandidates(
                    candidates = listOf("你好"),
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
        
        composeTestRule.onNodeWithText("nihao").assertIsDisplayed()
    }
    
    @Test
    fun candidateBarDisplaysComments() {
        composeTestRule.setContent {
            CandidateBar(
                state = CandidateBarState.ChineseCandidates(
                    candidates = listOf("你好"),
                    comments = listOf("wubi"),
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
    }
    
    @Test
    fun candidateBarDisplaysAssociationCandidates() {
        composeTestRule.setContent {
            CandidateBar(
                state = CandidateBarState.ChineseCandidates(
                    candidates = listOf("你好"),
                    associationCandidates = listOf("世界", "吗"),
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
    fun candidateItemDisplaysText() {
        composeTestRule.setContent {
            CandidateItem(
                text = "测试候选词",
                index = 0,
                onClick = {},
                textColor = KeyTextColor
            )
        }
        
        composeTestRule.onNodeWithText("测试候选词").assertIsDisplayed()
    }
    
    @Test
    fun candidateItemDisplaysComment() {
        composeTestRule.setContent {
            CandidateItem(
                text = "你好",
                index = 0,
                onClick = {},
                textColor = KeyTextColor,
                comment = "aaaa"
            )
        }
        
        composeTestRule.onNodeWithText("你好").assertIsDisplayed()
        composeTestRule.onNodeWithText("aaaa").assertIsDisplayed()
    }
}