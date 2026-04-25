package com.demeter.speech

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.demeter.speech.ui.DemeterMobileApp
import com.demeter.speech.ui.DemeterSpeechTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DemeterSpeechViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            DemeterSpeechTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val recordPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) viewModel.startRecording()
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {}
                LaunchedEffect(Unit) {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                DemeterMobileApp(
                    state = state,
                    actions = viewModel,
                    onRequireRecordingPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.bootstrap()
    }

    override fun onStop() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}
