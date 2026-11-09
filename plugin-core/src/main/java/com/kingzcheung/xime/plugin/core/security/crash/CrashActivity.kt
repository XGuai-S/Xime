package com.kingzcheung.xime.plugin.core.security.crash

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.core.model.PluginCrashInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CrashActivity : ComponentActivity() {

    private var crashInfo: PluginCrashInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        @Suppress("DEPRECATION")
        crashInfo = intent.getSerializableExtra(PluginCrashHandler.EXTRA_CRASH_INFO) as? PluginCrashInfo
        
        if (crashInfo == null) {
            crashInfo = createDefaultCrashInfo()
        }

        setContent {
            CrashScreen(
                crashInfo = crashInfo!!,
                onCloseApp = { handleCloseApp() },
                onRestartApp = { disablePlugin -> handleRestartApp(disablePlugin) }
            )
        }
    }

    private fun handleCloseApp() {
        finishAffinity()
    }

    private fun handleRestartApp(disablePlugin: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            crashInfo?.culpritPluginId?.let { pluginId ->
                if (disablePlugin) {
                    PluginManager.setPluginEnabled(pluginId, false)
                }
            }
        }
        
        val packageManager = packageManager
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        finishAffinity()
    }

    private fun createDefaultCrashInfo(): PluginCrashInfo {
        return PluginCrashInfo(
            throwable = RuntimeException("Unknown error"),
            culpritPluginId = null,
            defaultMessage = "An unexpected error occurred"
        )
    }
}

@Composable
private fun CrashScreen(
    crashInfo: PluginCrashInfo,
    onCloseApp: () -> Unit,
    onRestartApp: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Plugin Error",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    crashInfo.culpritPluginId?.let { pluginId ->
                        Text(
                            text = "Plugin ID:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = pluginId,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = "Message:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = crashInfo.defaultMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Stack Trace:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = crashInfo.throwable.stackTraceToString(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (crashInfo.culpritPluginId != null) {
                Button(
                    onClick = { onRestartApp(true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Disable Plugin and Restart")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = { onRestartApp(false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Restart App")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onCloseApp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Close App")
            }
        }
    }
}