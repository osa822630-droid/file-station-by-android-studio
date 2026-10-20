package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Requests Android 13+ notification permission before launching user-visible long work. */
@Composable
fun rememberBackgroundTaskStarter(
    onStart: () -> Unit,
    onNotificationDenied: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    val currentOnStart = rememberUpdatedState(onStart)
    val currentOnDenied = rememberUpdatedState(onNotificationDenied)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) currentOnDenied.value()
        currentOnStart.value()
    }

    return remember(context, permissionLauncher) {
        {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                currentOnStart.value()
            }
        }
    }
}
