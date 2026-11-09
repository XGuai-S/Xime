package com.kingzcheung.xime.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionHelper {
    const val PERMISSION_RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO
    const val REQUEST_CODE_RECORD_AUDIO = 1001
    
    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun requestRecordAudioPermission(context: Context) {
        val intent = Intent(context, com.kingzcheung.xime.MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("request_permission", PERMISSION_RECORD_AUDIO)
        context.startActivity(intent)
    }
}