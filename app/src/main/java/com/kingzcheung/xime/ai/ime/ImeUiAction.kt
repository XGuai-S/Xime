package com.kingzcheung.xime.ai.ime

import com.kingzcheung.xime.ai.data.KeyboardHeightMode
import com.kingzcheung.xime.ai.data.ToneStyle
import com.kingzcheung.xime.ai.data.VoiceIntentMode

/**
 * Unidirectional UI action bus for TypeFree v3.1.0+ keyboard panels.
 * Replaces 60+ scattered lambda parameters across [KeyboardScreen] and sub-panels,
 * and reserves [OpenJevCopilot] for the v3.2.0 Jev Copilot mode.
 */
sealed interface ImeUiAction {
    // Top-level navigation & overlays
    data class SelectTab(val tab: KeyboardTabMode) : ImeUiAction
    data object ToggleToolPanel : ImeUiAction
    data object ToggleToolPanelHeight : ImeUiAction
    data object OpenHelpMeWrite : ImeUiAction
    data object CloseSubPanel : ImeUiAction
    data object DismissSpeakToEdit : ImeUiAction
    data object OpenClipboard : ImeUiAction
    data object CloseClipboard : ImeUiAction
    data object ToggleStylePicker : ImeUiAction
    data object OpenStylePickerFromToolPanel : ImeUiAction
    data object TogglePunctuationStrip : ImeUiAction
    data class OpenHostApp(val tabIndex: Int) : ImeUiAction

    // v3.2.0 Jev Copilot mode actions
    data object OpenJevCopilot : ImeUiAction
    data class SelectJevStrategyCard(val index: Int) : ImeUiAction
    data object RefreshJevStrategies : ImeUiAction
    data object TriggerFullScreenPeekCapture : ImeUiAction
    data object ToggleJevContextInspector : ImeUiAction

    // Voice recording & Arc Translate
    data object StartVoiceRecording : ImeUiAction
    data object StopVoiceRecording : ImeUiAction
    data class MicPressDown(val mode: VoiceIntentMode) : ImeUiAction
    data object MicPressUp : ImeUiAction
    data object FinishHandsFree : ImeUiAction
    data object CancelVoiceRecording : ImeUiAction
    data object RetryLastAudio : ImeUiAction
    data object DismissError : ImeUiAction
    data class EnterArcTranslate(val startedFromHandsFree: Boolean) : ImeUiAction
    data class UpdateArcTranslate(val selectedLanguage: String, val cancelHovered: Boolean) : ImeUiAction
    data object ReleaseArcTranslate : ImeUiAction
    data class SelectTranslateLanguage(val language: String) : ImeUiAction

    // Help Me Write & Speak to Edit
    data class SelectQuickEditChip(val chip: QuickEditChip) : ImeUiAction
    data class SelectHelpWritePreset(val preset: HelpWritePromptPreset) : ImeUiAction
    data class RunHelpWriteDraftTransform(val preset: HelpWritePromptPreset) : ImeUiAction
    data object DirectGenerateHelpWrite : ImeUiAction
    data object AcceptChatContextDisclosure : ImeUiAction
    data object DeclineChatContextDisclosure : ImeUiAction
    data object ToggleIgnoreChatContext : ImeUiAction

    // v3.5.0 Ask AI Card panel actions
    data object OpenAskAiCard : ImeUiAction
    data object ReturnFromAskAiToQwerty : ImeUiAction
    data object RetryAskAiCard : ImeUiAction
    data object DismissAskAiAndClearEditor : ImeUiAction
    data object CopyAskAiAnswer : ImeUiAction
    data object CommitAskAiAnswerToEditor : ImeUiAction

    // Style & Preferences
    data object SelectAutoStyle : ImeUiAction
    data class SelectManualStyle(val style: ToneStyle) : ImeUiAction
    data object ToggleWhisperMode : ImeUiAction
    data object ToggleManualIncognito : ImeUiAction
    data object ToggleAiCompletion : ImeUiAction
    data object ToggleLiveWrite : ImeUiAction
    data object CycleInputScheme : ImeUiAction
    data class SelectKeyboardHeightLevel(val level: Int) : ImeUiAction
    data class SelectKeyboardHeightMode(val mode: KeyboardHeightMode) : ImeUiAction
    data class SelectKeyboardLiftLevel(val liftLevel: Int) : ImeUiAction

    // Typing & Editor actions
    data object TriggerUndo : ImeUiAction
    data class CommitText(val text: String) : ImeUiAction
    data class CommitQwertyChar(val char: String) : ImeUiAction
    data class CommaOrPeriodKey(val isComma: Boolean) : ImeUiAction
    data object ToggleFlypyLanguageMode : ImeUiAction
    data class SelectFlypyCandidate(val index: Int) : ImeUiAction
    data object ClearFlypyComposing : ImeUiAction
    data object FlushFlypyComposing : ImeUiAction
    data object ToggleFlypyCandidateSheet : ImeUiAction
    data object RequestMoreCandidates : ImeUiAction
    data class SelectPredictionCandidate(val index: Int) : ImeUiAction
    data class SelectAiCompletion(val text: String) : ImeUiAction
    data object DismissPredictions : ImeUiAction
    data class RunEditorShortcut(val action: EditorShortcutAction) : ImeUiAction
    data class InsertPairedSymbol(val left: String, val right: String) : ImeUiAction
    data class CommitSecondarySymbol(val symbol: String) : ImeUiAction
    data class MoveCursorByChars(val delta: Int) : ImeUiAction
    data object InsertSpace : ImeUiAction
    data object Backspace : ImeUiAction
    data object EnterOrNewline : ImeUiAction
    data object HideKeyboard : ImeUiAction
    data object SwitchKeyboard : ImeUiAction
    data object ShowImePicker : ImeUiAction

    // Clipboard actions
    data class PasteClipboardText(val text: String) : ImeUiAction
    data class TogglePinClipboardItem(val id: String) : ImeUiAction
    data class DeleteClipboardItem(val id: String) : ImeUiAction
    data object ClearUnpinnedClipboard : ImeUiAction
    data object SaveSelectionToClipboard : ImeUiAction
    data object EnableClipboard : ImeUiAction
    data object OpenClipboardSettings : ImeUiAction
}
