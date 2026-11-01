package com.pyvideotrans.subtitle

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pyvideotrans.subtitle.ui.diagnostics.DiagnosticsScreen
import com.pyvideotrans.subtitle.ui.home.HomeScreen
import com.pyvideotrans.subtitle.ui.models.ModelsScreen
import com.pyvideotrans.subtitle.ui.models.ModelsViewModel
import com.pyvideotrans.subtitle.ui.theme.SubtitleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            SubtitleTheme {
                val job = (applicationContext as App).subtitleJob
                val uiState by job.state.collectAsState()
                KeepScreenOn(uiState.keepScreenOn)
                val askNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(Unit) {
                    // Android 13+ needs consent before the task notification can be seen.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                var screen by rememberSaveable { mutableStateOf("home") }
                BackHandler(enabled = screen != "home") { screen = "home" }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (screen) {
                        "models" -> {
                            val viewModel: ModelsViewModel = viewModel()
                            ModelsScreen(
                                viewModel = viewModel,
                                onBack = { screen = "home" },
                            )
                        }

                        "diagnostics" -> DiagnosticsScreen(onBack = { screen = "home" })

                        else -> HomeScreen(
                            job = job,
                            onOpenModels = { screen = "models" },
                            onOpenDiagnostics = { screen = "diagnostics" },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A screen timing out is what stops a long run sitting on a desk, and unlike the power button
 * that one is ours to prevent. The flag binds to this window, so it stops mattering as soon as
 * another app is the visible one.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
