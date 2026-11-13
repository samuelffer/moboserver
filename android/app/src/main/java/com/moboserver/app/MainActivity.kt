package com.moboserver.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.moboserver.app.core.CrashLog
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.Store
import com.moboserver.app.ui.AppRoot
import com.moboserver.app.ui.CrashScreen
import com.moboserver.app.ui.MoboTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerController.init(this)
        Store.init(this)
        askNotificationPermission()

        // If the previous run died, show what happened instead of pretending
        // everything is fine.
        val crashReport = CrashLog.read(this)

        try {
            setContent {
                MoboTheme {
                    if (crashReport != null) {
                        CrashScreen(
                            report = crashReport,
                            onDismiss = {
                                CrashLog.clear(this)
                                recreate()
                            },
                        )
                    } else {
                        AppRoot()
                    }
                }
            }
        } catch (error: Throwable) {
            // Last resort: a plain view with the message, never a silent close.
            CrashLog.write(this, error)
            showEmergencyScreen(error)
        }
    }

    private fun showEmergencyScreen(error: Throwable) {
        val text = android.widget.TextView(this).apply {
            setPadding(48, 160, 48, 48)
            textSize = 12f
            setTextColor(0xFFFFC24B.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            text = "MoboServer não conseguiu iniciar a interface:\n\n" +
                (error.stackTraceToString().take(4000))
        }
        setContentView(android.widget.ScrollView(this).apply { addView(text) })
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
