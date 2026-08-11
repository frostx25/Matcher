package com.matcher.app.data.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.matcher.app.BuildConfig
import com.matcher.app.MainActivity
import com.matcher.app.R
import com.matcher.app.data.remote.SupabaseBackend
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface PushGateway {
    suspend fun register(): Boolean
    suspend fun unregister(): Boolean
}

object DisabledPushGateway : PushGateway {
    override suspend fun register(): Boolean = false
    override suspend fun unregister(): Boolean = false
}

class FirebasePushGateway(
    private val context: Context,
    private val client: SupabaseClient,
) : PushGateway {
    override suspend fun register(): Boolean {
        if (!initialize(context) || client.auth.currentUserOrNull() == null) return false
        FirebaseMessaging.getInstance().register().await()
        return registerInstallation(context, client, FirebaseInstallations.getInstance().id.await())
    }

    override suspend fun unregister(): Boolean {
        if (client.auth.currentUserOrNull() == null) return false
        val backendResult = runCatching {
            client.postgrest.rpc(
                function = "unregister_push_device",
                parameters = buildJsonObject {
                    put("target_installation_id", installationId(context).toString())
                },
            ).decodeAs<Boolean>()
        }.getOrDefault(false)
        if (initialize(context)) runCatching { FirebaseMessaging.getInstance().unregister().await() }
        return backendResult
    }

    companion object {
        private const val Preferences = "matcher_push_installation"
        private const val InstallationId = "installation_id"
        private const val PermissionAsked = "notification_permission_asked"
        const val MessageChannelId = "matcher_messages"

        fun isConfigured(): Boolean = listOf(
            BuildConfig.FIREBASE_API_KEY,
            BuildConfig.FIREBASE_APPLICATION_ID,
            BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_SENDER_ID,
        ).all(String::isNotBlank)

        fun initialize(context: Context): Boolean {
            if (!isConfigured()) return false
            if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context.applicationContext, options)
            }
            ensureNotificationChannel(context)
            return true
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    MessageChannelId,
                    "Mensagens",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Avisos privados de novas mensagens"
                    enableVibration(true)
                },
            )
        }

        fun shouldRequestPermission(context: Context): Boolean =
            isConfigured() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED &&
                !preferences(context).getBoolean(PermissionAsked, false)

        fun markPermissionAsked(context: Context) {
            preferences(context).edit().putBoolean(PermissionAsked, true).apply()
        }

        internal suspend fun registerInstallation(
            context: Context,
            client: SupabaseClient,
            firebaseInstallationId: String,
        ): Boolean {
            if (client.auth.currentUserOrNull() == null || firebaseInstallationId.isBlank()) return false
            return client.postgrest.rpc(
                function = "register_push_device",
                parameters = buildJsonObject {
                    put("target_installation_id", installationId(context).toString())
                    put("target_firebase_installation_id", firebaseInstallationId)
                },
            ).decodeAs()
        }

        private fun installationId(context: Context): UUID {
            val preferences = preferences(context)
            preferences.getString(InstallationId, null)?.let { value ->
                runCatching { UUID.fromString(value) }.getOrNull()?.let { return it }
            }
            return UUID.randomUUID().also { id ->
                preferences.edit().putString(InstallationId, id.toString()).apply()
            }
        }

        private fun preferences(context: Context) =
            context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
    }
}

@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class MatcherFirebaseMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        FirebasePushGateway.initialize(applicationContext)
    }

    override fun onRegistered(installationId: String) {
        if (!FirebasePushGateway.isConfigured()) return
        PushScope.launch {
            runCatching {
                FirebasePushGateway.registerInstallation(
                    applicationContext,
                    SupabaseBackend.client,
                    installationId,
                )
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = getSystemService(NotificationManager::class.java)
        FirebasePushGateway.ensureNotificationChannel(this)
        val conversationId = message.data["conversation_id"]
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let { putExtra(MainActivity.NotificationConversationExtra, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, FirebasePushGateway.MessageChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VibeAli")
            .setContentText("Nova mensagem")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(message.messageId?.hashCode() ?: System.nanoTime().toInt(), notification)
    }

    private companion object {
        val PushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            val error = task.exception
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(error ?: IllegalStateException("FCM_REGISTRATION_FAILED"))
        }
    }
