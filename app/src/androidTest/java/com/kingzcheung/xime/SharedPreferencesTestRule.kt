package com.kingzcheung.xime

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class SharedPreferencesTestRule : TestWatcher() {
    
    private var sharedPreferences: SharedPreferences? = null
    
    override fun starting(description: Description?) {
        super.starting(description)
        val context = ApplicationProvider.getApplicationContext<Context>()
        sharedPreferences = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        sharedPreferences?.edit()?.clear()?.apply()
    }
    
    override fun finished(description: Description?) {
        super.finished(description)
        sharedPreferences?.edit()?.clear()?.apply()
    }
    
    fun getSharedPreferences(): SharedPreferences {
        return sharedPreferences!!
    }
}