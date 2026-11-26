package com.coke.nestspace

import com.coke.nestspace.container.HeadlessMirrorWindow
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.coke.nestspace.container.control.ContainerUiClient
import com.coke.nestspace.integrations.service.BackgroundOverlay
import com.coke.nestspace.integrations.service.McpForegroundService
import com.coke.nestspace.features.navigation.VirtualApp
import com.coke.nestspace.features.navigation.DesktopNavigation
import com.coke.nestspace.design.theme.VirtualTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_TASKS = "com.coke.nestspace.OPEN_TASKS"
        const val EXTRA_TASK_ID = "com.coke.nestspace.TASK_ID"
    }

    /**
     * In a headless launch the target host is moved behind this Activity, so
     * the system back key lands here. Forward it to the mirrored container
     * instead; without this, back does nothing to the app in the small window.
     * Compose back handlers added later (settings, other tabs) still win.
     */
    private val mirrorBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val slot = HeadlessMirrorWindow.activeSlot()
            if (slot < 0) {
                fallbackBack()
                return
            }
            lifecycleScope.launch {
                if (ContainerUiClient.pressBack(this@MainActivity, slot) != true) {
                    fallbackBack()
                }
            }
        }

        /** Let the normal back stack handle it, then restore mirror handling. */
        private fun fallbackBack() {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            if (!isFinishing && !isDestroyed) {
                isEnabled = HeadlessMirrorWindow.isBackEnabled()
            }
        }
    }

    private val mirrorBackListener: (Boolean) -> Unit = { mirrorBack.isEnabled = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTaskIntent(intent)
        HeadlessMirrorWindow.bind(this)
        onBackPressedDispatcher.addCallback(this, mirrorBack)
        HeadlessMirrorWindow.addBackEnabledListener(mirrorBackListener)
        enableEdgeToEdge()
        setContent {
            VirtualTheme {
                VirtualApp()
            }
        }
        NestSpaceDeferredStartup.afterFirstFrame(window.decorView)
        // Virtual-app notifications are posted under the host package, so the
        // host needs the runtime notification permission on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        HeadlessMirrorWindow.onHostResumed(this)
        BackgroundOverlay.ensure(this)
        com.coke.nestspace.features.assistant.FloatingDotOverlay.onHostActivityResumed(this)
        // Application.onCreate happens before the process is foreground on
        // Android 16+, where starting a foreground service is rejected. Retry
        // while the UI is visible; ensureStarted respects an explicit Stop.
        McpForegroundService.ensureStarted(this)
    }

    override fun onPause() {
        HeadlessMirrorWindow.onHostPaused(this)
        super.onPause()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTaskIntent(intent)
    }

    private fun handleTaskIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TASKS, false) == true) {
            DesktopNavigation.requestTaskCenter(
                intent.getStringExtra(EXTRA_TASK_ID)
            )
            intent.removeExtra(EXTRA_OPEN_TASKS)
            intent.removeExtra(EXTRA_TASK_ID)
        }
    }

    override fun onDestroy() {
        HeadlessMirrorWindow.removeBackEnabledListener(mirrorBackListener)
        HeadlessMirrorWindow.unbind(this)
        super.onDestroy()
    }
}
