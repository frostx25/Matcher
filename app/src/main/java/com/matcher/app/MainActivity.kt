package com.matcher.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.matcher.app.ui.MatcherApp
import com.matcher.app.ui.theme.MatcherTheme

class MainActivity : ComponentActivity() {
    private var ageVerificationReturnSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordAgeVerificationReturn(intent.data)
        enableEdgeToEdge()
        setContent {
            MatcherTheme {
                MatcherApp(
                    useRemoteBackend = !(
                        BuildConfig.DEBUG && intent.getBooleanExtra(ForceDemoExtra, false)
                    ),
                    ageVerificationReturnSignal = ageVerificationReturnSignal,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordAgeVerificationReturn(intent.data)
    }

    private fun recordAgeVerificationReturn(uri: Uri?) {
        val isExpectedReturn = uri?.scheme == "matcher" &&
            uri.host == "age-verification" &&
            uri.pathSegments.singleOrNull() in AgeVerificationReturnPaths
        if (isExpectedReturn) ageVerificationReturnSignal += 1
    }

    companion object {
        const val ForceDemoExtra = "com.matcher.app.FORCE_DEMO"
        private val AgeVerificationReturnPaths = setOf("complete", "cancelled")
    }
}

