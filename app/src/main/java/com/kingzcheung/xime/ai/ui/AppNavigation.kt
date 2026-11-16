package com.kingzcheung.xime.ai.ui

object AppDestinations {
    const val EXTRA_OPEN_TAB = "extra_open_tab"
    const val EXTRA_SETTINGS_SUBTAB = "extra_settings_subtab"

    // Authentic TypeFree 4-Tab Bottom Navigation: Home / History / Dictionary / Account & API Settings
    const val TAB_HOME = 0
    const val TAB_HISTORY = 1
    const val TAB_DICTIONARY = 2
    const val TAB_SETTINGS = 3

    // Backward-compatible aliases so deep links from Keyboard / Error banners open Settings Studio
    const val TAB_API = TAB_SETTINGS
    const val TAB_STYLES = TAB_SETTINGS

    const val SUBTAB_API = 0
    const val SUBTAB_STYLES = 1
    const val SUBTAB_PREFERENCES = 2
}
