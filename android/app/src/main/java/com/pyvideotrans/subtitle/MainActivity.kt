package com.pyvideotrans.subtitle

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

                        else -> {
                            val job = (applicationContext as App).subtitleJob
                            HomeScreen(
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
}
