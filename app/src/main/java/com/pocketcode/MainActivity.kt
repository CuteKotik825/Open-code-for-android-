package com.pocketcode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pocketcode.ui.ChatController
import com.pocketcode.ui.ChatScreen
import com.pocketcode.ui.SessionsScreen
import com.pocketcode.ui.SettingsScreen

private enum class Screen { CHAT, SETTINGS, SESSIONS }

class MainActivity : ComponentActivity() {
    private val controller: ChatController
        get() = (application as PocketCodeApp).controller

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        val vm = controller
        setContent {
            AppTheme {
                var screen by remember { mutableStateOf(Screen.CHAT) }
                when (screen) {
                    Screen.CHAT -> ChatScreen(
                        vm,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenSessions = { screen = Screen.SESSIONS },
                    )
                    Screen.SETTINGS -> {
                        BackHandler { screen = Screen.CHAT }
                        SettingsScreen(
                            initial = vm.settings,
                            onSave = { vm.saveSettings(it) },
                            onBack = { screen = Screen.CHAT },
                        )
                    }
                    Screen.SESSIONS -> {
                        BackHandler { screen = Screen.CHAT }
                        SessionsScreen(vm, onBack = { screen = Screen.CHAT })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.appVisible = true
    }

    override fun onStop() {
        controller.appVisible = false
        super.onStop()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
