package com.matcher.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.matcher.app.ui.MatcherApp
import com.matcher.app.ui.theme.MatcherTheme
import com.matcher.app.data.push.FirebasePushGateway

class MainActivity : ComponentActivity() {
    private var ageVerificationReturnSignal by mutableIntStateOf(0)
    private var notificationConversationId by mutableStateOf<String?>(null)
    private var notificationConversationSignal by mutableIntStateOf(0)
    private var appResumeSignal by mutableIntStateOf(0)
    private var hasResumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebasePushGateway.initialize(applicationContext)
        recordAgeVerificationReturn(intent.data)
        recordNotificationConversation(intent)
        enableEdgeToEdge()
        setContent {
            MatcherTheme {
                MatcherApp(
                    useRemoteBackend = !(
                        BuildConfig.DEBUG && intent.getBooleanExtra(ForceDemoExtra, false)
                    ),
                    ageVerificationReturnSignal = ageVerificationReturnSignal,
                    notificationConversationId = notificationConversationId,
                    notificationConversationSignal = notificationConversationSignal,
                    appResumeSignal = appResumeSignal,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordAgeVerificationReturn(intent.data)
        recordNotificationConversation(intent)
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedOnce) {
            appResumeSignal += 1
        } else {
            hasResumedOnce = true
        }
    }

    private fun recordAgeVerificationReturn(uri: Uri?) {
        val isExpectedReturn = uri?.scheme == "matcher" &&
            uri.host == "age-verification" &&
            uri.pathSegments.singleOrNull() in AgeVerificationReturnPaths
        if (isExpectedReturn) ageVerificationReturnSignal += 1
    }

    private fun recordNotificationConversation(intent: Intent) {
        val candidate = intent.getStringExtra(NotificationConversationExtra)
        if (candidate != null && runCatching { java.util.UUID.fromString(candidate) }.isSuccess) {
            notificationConversationId = candidate
            notificationConversationSignal += 1
            intent.removeExtra(NotificationConversationExtra)
        }
    }

    companion object {
        const val ForceDemoExtra = "com.matcher.app.FORCE_DEMO"
        const val NotificationConversationExtra = "com.matcher.app.CONVERSATION_ID"
        private val AgeVerificationReturnPaths = setOf("complete", "cancelled")
    }
}

