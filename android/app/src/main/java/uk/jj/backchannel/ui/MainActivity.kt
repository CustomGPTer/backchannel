package uk.jj.backchannel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import uk.jj.backchannel.Runtime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Blocks screenshots, screen recording and the preview in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        Prefs.load(this)
        setContent {
            val mode by Prefs.theme.collectAsState()
            val dark = isDark(mode)
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BackchannelTheme(dark) { Root() }
        }
    }
}

@Composable
private fun Root() {
    val phase by Runtime.phase.collectAsState()
    val locked by Runtime.screensLocked.collectAsState()
    LaunchedEffect(locked, phase) {
        if (locked || phase != Runtime.Phase.OPEN) {
            Pictures.clear()
            uk.jj.backchannel.media.VoicePlayer.stop()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            phase == Runtime.Phase.SETUP -> SetupFlow()
            phase == Runtime.Phase.SEALED || locked -> UnlockScreen()
            else -> MainScreens()
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data object Settings : Screen
    data object Lock : Screen
    data object Backup : Screen
    data class Chat(val contactId: Long) : Screen
}

@Composable
private fun MainScreens() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val context = LocalContext.current

    // Ask once for permission to show the "New message" notification.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen == Screen.Lock || screen == Screen.Backup) Screen.Settings else Screen.Home
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            onOpenChat = { screen = Screen.Chat(it) },
            onAdd = { screen = Screen.Add },
            onSettings = { screen = Screen.Settings },
        )
        Screen.Add -> AddContactScreen(
            onBack = { screen = Screen.Home },
            onAdded = { screen = Screen.Chat(it) },
        )
        Screen.Settings -> SettingsScreen(
            onBack = { screen = Screen.Home },
            onLock = { screen = Screen.Lock },
            onBackup = { screen = Screen.Backup },
        )
        Screen.Lock -> LockSettingsScreen(onBack = { screen = Screen.Settings })
        Screen.Backup -> BackupScreen(onBack = { screen = Screen.Settings })
        is Screen.Chat -> ChatScreen(contactId = s.contactId, onBack = { screen = Screen.Home })
    }
}
