package com.kuyamcliff.compressor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.IntentCompat
import com.kuyamcliff.compressor.data.prefs.ThemeMode
import com.kuyamcliff.compressor.ui.info.OnboardingScreen
import com.kuyamcliff.compressor.ui.navigation.AppNavHost
import com.kuyamcliff.compressor.ui.navigation.ConfigureRequest
import com.kuyamcliff.compressor.ui.theme.CompressorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as CompressorApp).container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val prefs by container.prefsState.collectAsState()
            val scope = rememberCoroutineScope()
            CompressorTheme(mode = prefs?.themeMode ?: ThemeMode.SYSTEM) {
                val p = prefs
                when {
                    p == null -> Unit // first DataStore read is in flight (a few ms)
                    !p.onboardingDone -> OnboardingScreen(onDone = { scope.launch { container.preferences.update { it.copy(onboardingDone = true) } } })
                    else -> AppNavHost(onRequestNotificationPermission = ::requestNotifications)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !(application as CompressorApp).container.notifications.canPost()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Share-sheet input (SEND / SEND_MULTIPLE) and notification taps. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val container = (application as CompressorApp).container
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
        if (intent.hasExtra(EXTRA_JOB_ID)) container.session.requestOpenJob(jobId)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            container.session.startConfigure(ConfigureRequest(uris.take(50)))
            container.session.requestOpenConfigure()
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "open_job_id"
    }
}
